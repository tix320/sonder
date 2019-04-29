package io.titix.sonder.server;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.gitlab.tixtix320.kiwi.check.Try;
import com.gitlab.tixtix320.kiwi.observable.subject.Subject;
import com.gitlab.tixtix320.kiwi.util.IDGenerator;
import io.titix.sonder.client.OriginInvocationHandler;
import io.titix.sonder.extra.ClientID;
import io.titix.sonder.internal.*;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;


/**
 * @author Tigran.Sargsyan on 11-Dec-18
 */
@SuppressWarnings("Duplicates")
public final class Server {

	private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();

	private final ServerSocket serverSocket;

	private final Map<Class<?>, ?> originServices;

	private final Map<Class<?>, ?> endpointServices;

	private final Map<Method, OriginMethod> originsByMethod;

	private final Map<String, EndpointMethod> endpointsByPath;

	private final IDGenerator transmitterIdGenerator;

	private final Map<Long, Transmitter> transmitters;

	private final Map<Long, Exchanger<Object>> exchangers;

	public static Server run(int port, List<String> originPackages, List<String> endpointPackages) {
		ServerSocket serverSocket = Try.supplyAndGet(() -> new ServerSocket(port));

		OriginBoot originBoot = new OriginBoot(Config.getPackageClasses(originPackages));
		EndpointBoot endpointBoot = new EndpointBoot(Config.getPackageClasses(endpointPackages));

		Server server = new Server(serverSocket, originBoot, endpointBoot);
		CompletableFuture.runAsync(server::start, EXECUTOR).exceptionallyAsync(throwable -> {
			throwable.getCause().printStackTrace();
			return null;
		});
		return server;
	}

	private Server(ServerSocket serverSocket, OriginBoot originBoot, EndpointBoot endpointBoot) {
		this.serverSocket = serverSocket;

		this.originsByMethod = originBoot.getSignatures()
				.stream()
				.collect(toMap(signature -> signature.method, identity()));

		this.endpointsByPath = endpointBoot.getSignatures()
				.stream()
				.collect(toMap(signature -> signature.path, identity()));

		OriginInvocationHandler.Handler invocationHandler = createOriginInvocationHandler();
		this.originServices = originBoot.getSignatures()
				.stream()
				.peek(this::checkOriginExtraParamTypes)
				.peek(Server::checkDestination)
				.map(signature -> signature.clazz)
				.distinct()
				.collect(toMap(clazz -> clazz, clazz -> createOriginInstance(clazz, invocationHandler)));

		this.endpointServices = endpointBoot.getSignatures()
				.stream()
				.map(endpointMethod -> endpointMethod.clazz)
				.distinct()
				.collect(toMap(clazz -> clazz, this::creatEndpointInstance));

		this.transmitterIdGenerator = new IDGenerator();
		this.transmitters = new ConcurrentHashMap<>();
		this.exchangers = new ConcurrentHashMap<>();
	}

	@SuppressWarnings("unchecked")
	public <T> T getService(Class<T> clazz) {
		T service = (T) originServices.get(clazz);
		if (service == null) {
			throw new IllegalArgumentException("Service of " + clazz + " not found");
		}
		return service;
	}

	public void stop() {
		try {
			EXECUTOR.shutdownNow();
			serverSocket.close();
		}
		catch (IOException e) {
			throw new SonderException("Cannot close server socket.", e);
		}
	}

	private void start() {
		//noinspection InfiniteLoopStatement
		while (true) {
			Socket socket = Try.supplyAndGet(serverSocket::accept);

			CompletableFuture.runAsync(() -> {
				Transmitter transmitter = new Transmitter(socket);
				long connectedClientID = transmitterIdGenerator.next();
				transmitter.send(new Transfer(Headers.EMPTY, connectedClientID));

				transmitter.transfers().subscribe(transfer -> {
					Headers headers = transfer.headers;

					Long destinationClientId = headers.getLong("destination-client-id");
					if (destinationClientId == null) { // for server
						String path = headers.getString("path");
						EndpointMethod method = endpointsByPath.get(path);
						if (method == null) {
							throw new PathNotFoundException("Endpoint with path '" + path + "' not found");
						}

						Long sourceClientId = headers.getLong("source-client-id");
						Map<Class<? extends Annotation>, Object> extraArgResolver = Map.of(ClientID.class,
								sourceClientId);

						Object serviceInstance = endpointServices.get(method.clazz);
						Object[] args = appendExtraArgs((Object[]) transfer.content, method.extraParams,
								annotation -> extraArgResolver.get(annotation.annotationType()));
						Object result = method.invoke(serviceInstance, args);
						if (headers.getBoolean("need-response")) {
							headers = Headers.builder().header("is-response", true).build();
							transmitter.send(new Transfer(headers, result));
						}
					}
					else {
						Transmitter destinationTransmitter = transmitters.get(destinationClientId);
						if (destinationTransmitter == null) {
							throw new IllegalArgumentException(
									String.format("Client by id %s not found", destinationClientId));
						}

						if (headers.getBoolean("need-response")) {
							headers = Headers.builder()
									.header("path", headers.getString("path"))
									.header("transfer-key", headers.getLong("transfer-key"))
									.header("is-response", false)
									.header("need-response", true)
									.build();
							destinationTransmitter.send(new Transfer(headers, transfer.content));

						}
						else {
							headers = Headers.builder()
									.header("path", headers.getString("path"))
									.header("need-response", false)
									.header("is-response", false)
									.build();
							destinationTransmitter.send(new Transfer(headers, transfer.content));
						}
					}
				});

				transmitter.handleIncomingTransfers();

				transmitters.put(connectedClientID, transmitter);
			}, EXECUTOR).exceptionallyAsync(throwable -> {
				throwable.getCause().printStackTrace();
				return null;
			});
		}
	}

	private static void checkDestination(OriginMethod signature) {
		if (signature.destination == OriginMethod.Destination.SERVER) {
			throw new BootException(String.format(
					"In Server environment origin method '%s' in '%s' must have parameter annotated by @'%s'",
					signature.method, signature.clazz, ClientID.class.getSimpleName()));
		}
	}

	private Object[] appendExtraArgs(Object[] simpleArgs, List<ExtraParam> extraParams,
									 Function<Annotation, Object> extraArgResolver) {
		Object[] allArgs = new Object[simpleArgs.length + extraParams.size()];

		System.arraycopy(simpleArgs, 0, allArgs, 0, simpleArgs.length); // fill simple args

		for (ExtraParam extraParam : extraParams) {
			allArgs[extraParam.index] = extraArgResolver.apply(extraParam.annotation);
		}

		return allArgs;
	}

	private void checkOriginExtraParamTypes(OriginMethod originMethod) {
		Map<Class<? extends Annotation>, Class<?>> requiredTypes = Map.of(ClientID.class, long.class);

		Set<Class<? extends Annotation>> requiredExtraParams = Set.of(ClientID.class);

		List<ExtraParam> extraParams = originMethod.extraParams;

		Set<Class<? extends Annotation>> existingExtraParams = extraParams.stream()
				.map(extraParam -> extraParam.annotation.annotationType())
				.collect(Collectors.toSet());

		String nonExistingRequiredExtraParams = requiredExtraParams.stream()
				.filter(annotation -> !existingExtraParams.contains(annotation))
				.map(annotation -> "@" + annotation.getSimpleName())
				.collect(joining(",", "[", "]"));

		if (nonExistingRequiredExtraParams.length() > 2) { // is empty
			throw new BootException(
					String.format("Extra params %s are required in %s", nonExistingRequiredExtraParams, originMethod));
		}

		for (ExtraParam extraParam : extraParams) {
			Class<?> expectedType = requiredTypes.get(extraParam.annotation.annotationType());
			if (extraParam.type != expectedType) {
				throw new BootException(String.format("Extra param @%s must have type %s",
						extraParam.annotation.annotationType().getSimpleName(), expectedType.getName()));
			}

		}
	}

	private Object createOriginInstance(Class<?> clazz, OriginInvocationHandler.Handler invocationHandler) {
		return Proxy.newProxyInstance(clazz.getClassLoader(), new Class[]{clazz},
				new OriginInvocationHandler(originsByMethod::get, invocationHandler));
	}

	private Object creatEndpointInstance(Class<?> clazz) {
		return Try.supplyAndGet(() -> clazz.getConstructor().newInstance());
	}

	private OriginInvocationHandler.Handler createOriginInvocationHandler() {
		return (method, simpleArgs, extraArgs) -> {
			Long clientId = (Long) extraArgs.get(ClientID.class).getValue();

			Transmitter transmitter = transmitters.get(clientId);
			Headers headers = Headers.builder().header("path", method.path).build();
			if (method.needResponse) {
				Long transferKey = headers.getLong("transfer-key");
				headers = Headers.builder().header("transfer-key", transferKey).header("is-response", true).build();
				Subject<Object> result = Subject.single();
				Exchanger<Object> exchanger = new Exchanger<>();
				exchangers.put(transferKey, exchanger);
				CompletableFuture.runAsync(() -> Try.runAndRethrow(() -> result.next(exchanger.exchange(null))))
						.whenCompleteAsync((v, throwable) -> Optional.ofNullable(throwable)
								.ifPresent(t -> t.getCause().printStackTrace()));
				transmitter.send(new Transfer(headers, simpleArgs));
				return result.asObservable().one();
			}
			else {
				headers = Headers.builder().header("need-response", false).build();
				transmitter.send(new Transfer(headers, simpleArgs));
				return null;
			}
		};
	}
}
