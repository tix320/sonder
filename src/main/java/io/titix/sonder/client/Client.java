package io.titix.sonder.client;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Exchanger;
import java.util.function.Function;

import com.gitlab.tixtix320.kiwi.check.Try;
import com.gitlab.tixtix320.kiwi.observable.subject.Subject;
import com.gitlab.tixtix320.kiwi.util.IDGenerator;
import io.titix.sonder.extra.ClientID;
import io.titix.sonder.internal.*;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

/**
 * @author tix32 on 20-Dec-18
 */
@SuppressWarnings("Duplicates")
public final class Client {

	private final long id;

	private final Transmitter transmitter;

	private final Map<Class<?>, ?> originServices;

	private final Map<Class<?>, ?> endpointServices;

	private final Map<Method, OriginMethod> originsByMethod;

	private final Map<String, EndpointMethod> endpointsByPath;

	private final IDGenerator transferIdGenerator;

	private final Map<Long, Exchanger<Object>> exchangers;

	public static Client run(String host, int port, List<String> originPackages, List<String> endpointPackages) {
		Socket socket = Try.supplyAndGet(() -> new Socket(host, port));
		OriginBoot originBoot = new OriginBoot(Config.getPackageClasses(originPackages));
		EndpointBoot endpointBoot = new EndpointBoot(Config.getPackageClasses(endpointPackages));
		return new Client(new Transmitter(socket), originBoot, endpointBoot);
	}

	private Client(Transmitter transmitter, OriginBoot originBoot, EndpointBoot endpointBoot) {
		this.transmitter = transmitter;

		this.originsByMethod = originBoot.getSignatures()
				.stream()
				.collect(toMap(signature -> signature.method, identity()));

		this.endpointsByPath = endpointBoot.getSignatures()
				.stream()
				.collect(toMap(signature -> signature.path, identity()));

		OriginInvocationHandler.Handler invocationHandler = createOriginInvocationHandler();
		this.originServices = originBoot.getSignatures()
				.stream()
				.map(signature -> signature.clazz)
				.distinct()
				.collect(toMap(clazz -> clazz, clazz -> createOriginInstance(clazz, invocationHandler)));

		this.endpointServices = endpointBoot.getSignatures()
				.stream()
				.map(endpointMethod -> endpointMethod.clazz)
				.distinct()
				.collect(toMap(clazz -> clazz, this::creatEndpointInstance));

		this.exchangers = new ConcurrentHashMap<>();
		this.transferIdGenerator = new IDGenerator();

		this.id = resolveId();

		handleIncomingRequests();
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
		transmitter.close();
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
			Headers headers;

			switch (method.destination) {
				case SERVER:
					headers = Headers.builder().header("path", method.path).header("source-client-id", id).build();
					break;
				case CLIENT:
					ExtraArg extraArg = extraArgs.get(ClientID.class);
					Object clientId = extraArg.getValue();
					headers = Headers.builder().header("path", method.path)
							.header("source-client-id", id)
							.header("destination-client-id", clientId)
							.build();
					break;
				default:
					throw new IllegalStateException();
			}

			if (method.needResponse) {
				long transferKey = transferIdGenerator.next();
				headers = headers.compose().header("transfer-key", transferKey).header("need-response", true).build();
				Transfer transfer = new Transfer(headers, simpleArgs.toArray());

				Subject<Object> result = Subject.single();
				Exchanger<Object> exchanger = new Exchanger<>();
				exchangers.put(transferKey, exchanger);
				CompletableFuture.runAsync(() -> Try.runAndRethrow(() -> result.next(exchanger.exchange(null))))
						.whenCompleteAsync((v, throwable) -> Optional.ofNullable(throwable)
								.ifPresent(t -> t.getCause().printStackTrace()));

				CompletableFuture.runAsync(() -> this.transmitter.send(transfer))
						.whenCompleteAsync((v, throwable) -> Optional.ofNullable(throwable)
								.ifPresent(t -> t.getCause().printStackTrace()));

				return result.asObservable().one();
			}
			else {
				headers = headers.compose().header("need-response", false).build();

				Transfer transfer = new Transfer(headers, simpleArgs.toArray());

				CompletableFuture.runAsync(() -> this.transmitter.send(transfer))
						.whenCompleteAsync((v, throwable) -> Optional.ofNullable(throwable)
								.ifPresent(t -> t.getCause().printStackTrace()));

				return null;
			}
		};
	}

	private Long resolveId() {
		Exchanger<Long> clientIdExchanger = new Exchanger<>();
		transmitter.transfers()
				.one()
				.subscribe(transfer -> Try.runAndRethrow(() -> clientIdExchanger.exchange((Long) transfer.content)));
		transmitter.handleIncomingTransfers();

		return Try.supplyAndGet(() -> clientIdExchanger.exchange(null));
	}

	private void handleIncomingRequests() {
		transmitter.transfers().subscribe(transfer -> {
			Headers headers = transfer.headers;
			boolean isResponse = headers.getBoolean("is-response");
			if (isResponse) {
				processResponse(transfer);
			}
			else {
				processRequest(transfer);
			}

		});
	}

	private void processResponse(Transfer transfer) {
		Long transferKey = transfer.headers.getLong("transfer-key");
		exchangers.computeIfPresent(transferKey, (key, exchanger) -> {
			Try.runAndRethrow(() -> exchanger.exchange(transfer.content));
			return null;
		});
	}

	private void processRequest(Transfer transfer) {
		Headers headers = transfer.headers;

		String path = headers.getString("path");
		EndpointMethod method = endpointsByPath.get(path);
		if (method == null) {
			throw new PathNotFoundException("Endpoint with path '" + path + "' not found");
		}

		Long sourceClientId = headers.getLong("source-client-id");


		Map<Class<? extends Annotation>, Object> extraArgResolver = new HashMap<>();
		extraArgResolver.put(ClientID.class, sourceClientId);

		Object serviceInstance = endpointServices.get(method.clazz);
		Object[] args = appendExtraArgs((Object[]) transfer.content, method.extraParams,
				annotation -> extraArgResolver.get(annotation.annotationType()));
		Object result = method.invoke(serviceInstance, args);

		if (headers.getBoolean("need-response")) {
			headers = Headers.builder()
					.header("transfer-key", headers.getLong("transfer-key"))
					.header("is-response", true)
					.build();
			this.transmitter.send(new Transfer(headers, result));
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
}
