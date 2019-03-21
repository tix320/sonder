package io.titix.sonder.internal;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import io.titix.kiwi.check.Try;
import io.titix.kiwi.rx.Observable;
import io.titix.kiwi.rx.Subject;
import io.titix.kiwi.util.IdGenerator;
import io.titix.kiwi.util.PostPool;
import io.titix.kiwi.util.Threads;
import io.titix.sonder.internal.boot.*;

/**
 * @author Tigran.Sargsyan on 26-Dec-18
 */
public final class Communicator {

	private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(Threads::daemon);

	private static final IdGenerator comIdGenerator = new IdGenerator();

	private static final PostPool<Map<Class<?>, Object>> originsPool = new PostPool<>();

	private static final PostPool<Map<Class<?>, Object>> endpointsPool = new PostPool<>();

	private final Long id;

	private final Transmitter transmitter;

	private final Map<Method, OriginSignature> originsByMethods;

	private final Map<String, EndpointSignature> endpointsByPaths;

	private final Map<Class<?>, Object> origins;

	private final Map<Class<?>, Object> endpoints;

	private final IdGenerator transferIdGenerator;

	private final Map<Long, Exchanger<Object>> exchangers;

	private final Map<String, Supplier<?>> extraArgsResolver;

	public Communicator(Socket socket, OriginBoot originBoot, EndpointBoot endpointBoot) {
		this.id = comIdGenerator.next();
		this.extraArgsResolver = createExtraArgsResolver();
		this.transmitter = new Transmitter(socket);
		this.exchangers = new ConcurrentHashMap<>();
		this.transferIdGenerator = new IdGenerator();
		this.originsByMethods = originBoot.getSignatures()
				.stream()
				.collect(Collectors.toMap(signature -> signature.method, signature -> signature));
		this.endpointsByPaths = endpointBoot.getSignatures()
				.stream()
				.collect(Collectors.toMap(signature -> signature.path, signature -> signature));
		this.origins = originsPool.get(() -> originBoot.createServices(new OriginHandler()));
		this.endpoints = endpointsPool.get(endpointBoot::createServices);
		runReceiver();
	}

	public Long id() {
		return id;
	}

	@SuppressWarnings("unchecked")
	public <T> T getService(Class<T> clazz) {
		return (T) origins.get(clazz);
	}

	public void close() {
		originsPool.release(origins);
		endpointsPool.release(endpoints);
		transmitter.close();
		comIdGenerator.free(id);
	}

	private void runReceiver() {
		Future<?> future = EXECUTOR.submit(() -> {
			//noinspection InfiniteLoopStatement
			while (true) {
				unboxTransfer(transmitter.receive());
			}
		});
		Threads.handleFutureEx(future);
	}

	private Observable<?> send(String path, Object[] args, boolean needResponse) {
		Long transferKey = transferIdGenerator.next();

		Headers headers = Headers.builder().path(path).id(transferKey).needResponse(needResponse).build();
		Transfer transfer = new Transfer(headers, args);

		Subject<Object> result = Subject.single();
		if (needResponse) {
			Exchanger<Object> exchanger = new Exchanger<>();
			Threads.runAsync(() -> result.next(exchanger.exchange(null)), EXECUTOR);
			exchangers.put(transferKey, exchanger);
		}
		Threads.runAsync(() -> this.transmitter.send(transfer), EXECUTOR);

		return result.asObservable().one();
	}

	private void unboxTransfer(Transfer transfer) {
		Headers headers = transfer.headers;
		Long id = headers.getId();
		if (headers.isResponse()) {
			Exchanger<Object> exchanger = exchangers.get(id);
			Try.run(() -> exchanger.exchange(transfer.content)).rethrow(InternalException::new);

			exchangers.remove(id);
			transferIdGenerator.free(id);
		}
		else {
			Object result = invokeEndpoint(headers.getPath(), (Object[]) transfer.content);
			if (headers.needResponse()) {
				this.transmitter.send(new Transfer(Headers.builder().id(id).isResponse(true).build(), result));
			}
		}
	}

	private Object invokeEndpoint(String path, Object[] args) {
		EndpointSignature signature = endpointsByPaths.get(path);
		if (signature == null) {
			throw new BootException("Endpoint with path '" + path + "' not found");
		}
		Object[] allArgs = resolveArgs(signature, args);
		try {
			return signature.method.invoke(endpoints.get(signature.clazz), allArgs);
		}
		catch (IllegalAccessException | InvocationTargetException e) {
			throw new InternalException("Cannot invoke method " + signature.method, e);
		}
		catch (IllegalArgumentException e) {
			throw illegalEndpointSignature(signature, allArgs);
		}
	}

	private Object[] resolveArgs(EndpointSignature signature, Object[] realArgs) {
		if (signature.params.stream().filter(param -> !param.isExtra).count() != realArgs.length) {
			throw illegalEndpointSignature(signature, realArgs);
		}
		Object[] allArgs = new Object[signature.params.size()];
		int raIndex = 0;
		for (int i = 0; i < allArgs.length; i++) {
			Param param = signature.params.get(i);
			allArgs[i] = param.isExtra ? extraArgsResolver.get(param.key).get() : realArgs[raIndex++];
		}
		return allArgs;
	}

	private BootException illegalEndpointSignature(EndpointSignature signature, Object[] args) {
		return new BootException("Illegal signature of method '" + signature.method.getName() + "' in " + signature.clazz + ". Expected following parameters "
				+ Arrays.stream(args)
				.map(arg -> arg.getClass().getSimpleName())
				.collect(Collectors.joining(",", "[", "]")));
	}

	private Map<String, Supplier<?>> createExtraArgsResolver() {
		return Map.of("client-id", this::id);
	}

	private final class OriginHandler implements InvocationHandler {

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) {
			if (method.getDeclaringClass() == Object.class) {
				throw new UnsupportedOperationException("This method does not allowed on services");
			}

			OriginSignature signature = originsByMethods.get(method);

			if (args == null) {
				args = new Object[0];
			}

			if (signature.needResponse) {
				return send(signature.path, args, true);
			}
			else {
				send(signature.path, args, false);
				return null;
			}
		}
	}
}
