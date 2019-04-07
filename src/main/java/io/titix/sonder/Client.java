package io.titix.sonder;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import io.titix.kiwi.check.Try;
import io.titix.sonder.internal.*;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

/**
 * @author tix32 on 20-Dec-18
 */
@SuppressWarnings("Duplicates")
public final class Client {

	private final long id;

	private final Communicator communicator;

	private final Map<Class<?>, ?> originServices;

	private final Map<Class<?>, ?> endpointServices;

	private final Map<Method, OriginMethod> originsByMethod;

	private final Map<String, EndpointMethod> endpointsByPath;

	public static Client run(String host, int port, List<String> originPackages, List<String> endpointPackages) {
		OriginBoot originBoot = new OriginBoot(Config.getPackageClasses(originPackages));
		EndpointBoot endpointBoot = new EndpointBoot(Config.getPackageClasses(endpointPackages));
		Socket socket = Try.supplyAndGet(() -> new Socket(host, port));
		return new Client(socket, originBoot, endpointBoot);
	}

	private Client(Socket socket, OriginBoot originBoot, EndpointBoot endpointBoot) {

		InvocationHandler originInvocationHandler = createOriginInvocationHandler();
		this.originServices = originBoot.getSignatures()
				.stream()
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


		Transmitter transmitter = new Transmitter(socket);
		AtomicLong clientIdHolder = new AtomicLong();
		transmitter.transfers().one().subscribe(transfer -> clientIdHolder.set((long) transfer.content));
		transmitter.handleIncomingTransfers();

		this.id = clientIdHolder.get();

		communicator = new Communicator(transmitter, (headers, args) -> {
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
		if (communicator != null) {
			communicator.close();
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

			Headers headers;

			switch (signature.destination) {
				case SERVER:
					headers = Headers.builder().header("path", signature.path).header("source-client-id", id).build();
					break;
				case CLIENT:
					Object clientId = extraArgs.get("to-client-id");
					headers = Headers.builder()
							.header("path", signature.path)
							.header("source-client-id", id)
							.header("destination-client-id", clientId)
							.build();
					break;
				default:
					throw new IllegalStateException();
			}

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
