package io.titix.sonder;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.titix.kiwi.check.Try;
import io.titix.kiwi.util.IDGenerator;
import io.titix.sonder.extra.ClientID;
import io.titix.sonder.internal.*;

import static java.util.function.Function.identity;
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

	private final IDGenerator communicatorIdGenerator;

	private final Map<Long, Communicator> communicators;

	public static Server run(int port, List<String> originPackages, List<String> endpointPackages) {
		ServerSocket serverSocket = Try.supplyAndGet(() -> new ServerSocket(port));

		OriginBoot originBoot = new OriginBoot(Config.getPackageClasses(originPackages));
		EndpointBoot endpointBoot = new EndpointBoot(Config.getPackageClasses(endpointPackages));

		Server server = new Server(serverSocket, originBoot, endpointBoot);
		CompletableFuture.runAsync(server::start, EXECUTOR);
		return server;
	}

	private Server(ServerSocket serverSocket, OriginBoot originBoot, EndpointBoot endpointBoot) {
		this.serverSocket = serverSocket;

		InvocationHandler originInvocationHandler = createOriginInvocationHandler();
		this.originServices = originBoot.getSignatures()
				.stream()
				.peek(Server::checkDestination)
				.map(signature -> signature.clazz)
				.distinct()
				.collect(toMap(clazz -> clazz, clazz -> createOriginInstance(clazz, originInvocationHandler)));

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

		this.communicatorIdGenerator = new IDGenerator();
		this.communicators = new ConcurrentHashMap<>();
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
				long connectedClientID = communicatorIdGenerator.next();
				transmitter.send(new Transfer(Headers.EMPTY, connectedClientID));
				transmitter.handleIncomingTransfers();

				Communicator communicator = new Communicator(transmitter, (headers, args) -> {
					String path = headers.get("path", String.class);

					EndpointMethod endpoint = endpointsByPath.get(path);
					if (endpoint == null) {
						throw new PathNotFoundException("Endpoint with path '" + path + "' not found");
					}
					Object serviceInstance = endpointServices.get(endpoint.clazz);

					String sourceClientId = headers.get("source-client-id", String.class);
					Map<String, Object> endpointExtraArgResolvers = Map.of("client-id", sourceClientId);

					return endpoint.invoke(serviceInstance, args, endpointExtraArgResolvers::get);
				});
				communicators.put(connectedClientID, communicator);
			}, EXECUTOR);
		}
	}

	private static void checkDestination(OriginMethod signature) {
		if (signature.destination == OriginMethod.Destination.SERVER) {
			throw new BootException(String.format(
					"In Server environment origin method '%s' in '%s' must have parameter annotated by @'%s'",
					signature.method, signature.clazz, ClientID.class.getSimpleName()));
		}
	}

	private Object createOriginInstance(Class<?> clazz, InvocationHandler invocationHandler) {
		return Proxy.newProxyInstance(clazz.getClassLoader(), new Class[]{clazz}, invocationHandler);

	}

	private Object creatEndpointInstance(Class<?> clazz) {
		return Try.supplyAndGet(() -> clazz.getConstructor().newInstance());
	}

	private InvocationHandler createOriginInvocationHandler() {
		return (proxy, method, args) -> {
			if (method.getDeclaringClass() == Object.class) {
				throw new UnsupportedOperationException("This method does not allowed on origin services");
			}

			OriginMethod signature = originsByMethod.get(method);
			List<Param> simpleParams = signature.simpleParams;
			List<ExtraParam> extraParams = signature.extraParams;

			Object[] simpleArgs = new Object[simpleParams.size()];
			System.arraycopy(args, 0, simpleArgs, 0, simpleArgs.length); // fill simple args

			Map<String, Object> extraArgs = new HashMap<>();
			int extraParamsIndex = 0;
			int firstExtraIndex = simpleArgs.length;
			for (int i = firstExtraIndex; i < args.length; i++) {
				String key = extraParams.get(extraParamsIndex++).key;
				Object value = args[i];
				extraArgs.put(key, value);
			}

			long clientId = (long) extraArgs.get("to-client-id");

			Communicator communicator = communicators.get(clientId);
			Headers headers = Headers.builder().header("path", signature.path).build();
			if (signature.needResponse) {
				return communicator.sendRequest(headers, simpleArgs);
			}
			else {
				communicator.sendUnresponsiveRequest(headers, simpleArgs);
				return null;
			}
		};
	}
}
