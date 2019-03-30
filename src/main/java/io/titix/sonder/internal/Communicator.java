package io.titix.sonder.internal;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Exchanger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import io.titix.kiwi.check.Try;
import io.titix.kiwi.rx.Observable;
import io.titix.kiwi.rx.Subject;
import io.titix.kiwi.util.IdGenerator;
import io.titix.kiwi.util.Threads;
import io.titix.sonder.internal.boot.BootException;
import io.titix.sonder.internal.boot.EndpointSignature;
import io.titix.sonder.internal.boot.OriginSignature;

import static java.util.function.Function.identity;

/**
 * @author Tigran.Sargsyan on 26-Dec-18
 */
public final class Communicator {

	private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(Threads::daemon);

	private static final IdGenerator comIdGenerator = new IdGenerator();

	private final long id;

	private final Transmitter transmitter;

	private final Map<Class<?>, Object> originInstances;

	private final Map<String, EndpointSignature> endpointsByPaths;

	private final Map<Class<?>, Object> endpointInstances;

	private final IdGenerator transferIdGenerator;

	private final Map<Long, Exchanger<Object>> exchangers;

	private final Map<String, Supplier<?>> extraArgResolvers;

	public Communicator(Transmitter transmitter, List<OriginSignature> origins, List<EndpointSignature> endpoints) {
		this.id = comIdGenerator.next();
		this.extraArgResolvers = createExtraArgsResolver();
		this.transmitter = transmitter;
		this.exchangers = new ConcurrentHashMap<>();
		this.transferIdGenerator = new IdGenerator();
		this.originInstances = origins.stream()
				.collect(Collectors.toMap(signature -> signature.clazz, this::createOriginInstance));
		this.endpointInstances = endpoints.stream()
				.collect(Collectors.toMap(signature -> signature.clazz, this::creatEndpointInstance));
		this.endpointsByPaths = endpoints.stream().collect(Collectors.toMap(signature -> signature.path, identity()));
		transmitter.transfers().subscribe(this::resolveTransfer);
	}

	public long id() {
		return id;
	}

	@SuppressWarnings("unchecked")
	public <T> T getService(Class<T> clazz) {
		return (T) originInstances.get(clazz);
	}

	public void close() {
		comIdGenerator.release(id);
		transmitter.close();
	}

	private Object createOriginInstance(OriginSignature signature) {
		InvocationHandler invocationHandler = (proxy, method, args) -> {
			if (method.getDeclaringClass() == Object.class) {
				throw new UnsupportedOperationException("This method does not allowed on origin services");
			}

			if (args == null) {
				args = new Object[0];
			}

			if (signature.needResponse) {
				return sendWithResponse(signature.path, args);
			}
			else {
				sendWithoutResponse(signature.path, args);
				return null;
			}
		};

		return Proxy.newProxyInstance(signature.clazz.getClassLoader(), new Class[]{signature.clazz},
				invocationHandler);
	}

	private Object creatEndpointInstance(EndpointSignature signature) {
		return Try.supply(() -> signature.clazz.getConstructor().newInstance()).getOrElseThrow(InternalException::new);
	}

	private void sendWithoutResponse(String path, Object[] args) {
		long transferKey = transferIdGenerator.next();

		Headers headers = new Headers(transferKey, path, false, false);
		Transfer transfer = new Transfer(headers, args);

		Threads.runAsync(() -> this.transmitter.send(transfer), EXECUTOR);
	}

	private Observable<?> sendWithResponse(String path, Object[] args) {
		long transferKey = transferIdGenerator.next();

		Headers headers = new Headers(transferKey, path, false, true);
		Transfer transfer = new Transfer(headers, args);

		Subject<Object> result = Subject.single();
		Exchanger<Object> exchanger = new Exchanger<>();
		exchangers.put(transferKey, exchanger);
		Threads.runAsync(() -> result.next(exchanger.exchange(null)), EXECUTOR);
		Threads.runAsync(() -> this.transmitter.send(transfer), EXECUTOR);

		return result.asObservable();
	}

	private void resolveTransfer(Transfer transfer) {
		Headers headers = transfer.headers;
		if (headers.isResponse()) {
			handleResponse(transfer);
		}
		else {
			handleRequest(transfer);
		}
	}

	private void handleResponse(Transfer transfer) {
		Exchanger<Object> exchanger = exchangers.get(transfer.headers.getId());
		Try.run(() -> exchanger.exchange(transfer.content)).rethrow(InternalException::new);

		exchangers.remove(id);
		transferIdGenerator.release(id);
	}

	private void handleRequest(Transfer transfer) {
		Headers headers = transfer.headers;
		String path = headers.getPath();
		EndpointSignature endpoint = endpointsByPaths.get(path);
		if (endpoint == null) {
			throw new BootException("Endpoint with path '" + path + "' not found");
		}
		Object[] args = (Object[]) transfer.content;
		Object instance = endpointInstances.get(endpoint.clazz);
		Object result = endpoint.invoke(instance, args, key -> extraArgResolvers.get(key).get());
		if (headers.needResponse()) {
			this.transmitter.send(new Transfer(new Headers(headers.getId(), null, true, false), result));
		}
	}

	private Map<String, Supplier<?>> createExtraArgsResolver() {
		return Map.of("client-id", this::id);
	}
}
