package io.titix.sonder.server;

import java.io.IOException;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import com.gitlab.tixtix320.kiwi.check.Try;
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

		OriginInvocationHandler.Handler invocationHandler = createOriginInvocationHandler();
		this.originServices = originBoot.getSignatures()
				.stream()
				.peek(this::checkOriginExtraParamTypes)
				.peek(Server::checkDestination)
				.map(signature -> signature.clazz)
				.distinct().collect(toMap(clazz -> clazz, clazz -> createOriginInstance(clazz, invocationHandler)));

		this.endpointServices = endpointBoot.getSignatures()
				.stream()
				.map(endpointMethod -> endpointMethod.clazz)
				.distinct()
				.collect(toMap(clazz -> clazz, this::creatEndpointInstance));

		this.originsByMethod = originBoot.getSignatures()
				.stream()
				.collect(toMap(signature -> signature.method, identity()));

		this.endpointsByPath = endpointBoot.getSignatures()
				.stream()
				.collect(toMap(signature -> signature.path, identity()));

		this.transmitterIdGenerator = new IDGenerator();
		this.transmitters = new ConcurrentHashMap<>();
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
				transmitter.handleIncomingTransfers();

				Communicator communicator = new Communicator(transmitter, (headers, args) -> {
					String path = headers.get("path", String.class);

					Long sourceClientId = headers.getLong("source-client-id");
					Map<Class<? extends Annotation>, Object> endpointExtraArgResolvers = Map.of(ClientID.class,
							sourceClientId);

					Long destinationClientId = headers.getLong("destination-client-id");
					if (destinationClientId == null) { // for server
						EndpointMethod endpoint = endpointsByPath.get(path);
						if (endpoint == null) {
							throw new PathNotFoundException("Endpoint with path '" + path + "' not found");
						}
						Object serviceInstance = endpointServices.get(endpoint.clazz);
						return (Serializable) endpoint.invoke(serviceInstance, args, endpointExtraArgResolvers::get);
					}
					else {
						Communicator destinationCommunicator = transmitters.get(destinationClientId);
						if (destinationCommunicator == null) {
							throw new IllegalArgumentException(
									String.format("Client by id %s not found", destinationClientId));
						}

						if (headers.getBoolean("need-response")) {
							return destinationCommunicator.invokeRemote(headers, args).get();

						}
						else {
							destinationCommunicator.invokeRemoteUnresponsive(headers, args);
							return null;
						}
					}
				});
				transmitters.put(connectedClientID, communicator);
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

	private void checkOriginExtraParamTypes(OriginMethod originMethod) {
		Map<Class<? extends Annotation>, Class<?>> requiredTypes = Map.of(ClientID.class, long.class);

		Set<Class<? extends Annotation>> requiredExtraParams = Set.of(ClientID.class);

		List<ExtraParam> extraParams = originMethod.extraParams;

		Set<Class<? extends Annotation>> existingExtraParams = extraParams.stream()
				.map(extraParam -> extraParam.annotationArguments)
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
			Class<?> expectedType = requiredTypes.get(extraParam.annotationArguments);
			if (extraParam.type != expectedType) {
				throw new BootException(String.format("Extra param @%s must have type %s",
						extraParam.annotationArguments.getSimpleName(), expectedType.getName()));
			}

		}
	}

	private Object createOriginInstance(Class<?> clazz, InvocationHandler invocationHandler) {
		return Proxy.newProxyInstance(clazz.getClassLoader(), new Class[]{clazz}, invocationHandler);

	}

	private Object creatEndpointInstance(Class<?> clazz) {
		return Try.supplyAndGet(() -> clazz.getConstructor().newInstance());
	}

	private OriginInvocationHandler.Handler createOriginInvocationHandler() {
		return (method, simpleArgs, extraArgs) -> {
			Long clientId = (Long) extraArgs.get(ClientID.class).getValue();

			Communicator communicator = transmitters.get(clientId);
			Headers headers = Headers.builder().header("path", signature.path).build();
			if (signature.needResponse) {
				return communicator.invokeRemote(headers, simpleArgs);
			}
			else {
				communicator.invokeRemoteUnresponsive(headers, simpleArgs);
				return null;
			}
		};
	}
}
