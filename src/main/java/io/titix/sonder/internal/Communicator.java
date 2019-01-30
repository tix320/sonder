package io.titix.sonder.internal;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import io.titix.kiwi.check.CheckedFunction;
import io.titix.kiwi.check.Try;
import io.titix.kiwi.util.IdGenerator;
import io.titix.kiwi.util.Pool;
import io.titix.kiwi.util.Threads;
import io.titix.sonder.Callback;

/**
 * @author Tigran.Sargsyan on 26-Dec-18
 */
public final class Communicator {

	private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(Threads::daemon);

	private static final Pool<Map<Class<?>, Object>> proxyPool = new Pool<>();

	private static final IdGenerator comIdGenerator = new IdGenerator();

	private final Boot boot;

	private final Long id;

	private final Map<Class<?>, Object> endpoints;

	private final Map<Class<?>, Object> origins;

	private final Transmitter transmitter;

	private final Map<Long, Callback<Object>> callbacks;

	private final IdGenerator transferIdGenerator;

	private final Map<String, Supplier<Object>> extraArgsResolver;

	public Communicator(Socket socket, Boot boot) {
		this.id = comIdGenerator.next();
		this.boot = boot;
		this.extraArgsResolver = createExtraArgsResolver();
		this.transmitter = new Transmitter(socket);
		this.callbacks = new ConcurrentHashMap<>();
		this.transferIdGenerator = new IdGenerator();
		this.origins = proxyPool.getProxies(this::createOriginProxies);
		this.endpoints = createEndpointInstances(boot.getEndpointServices());
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
		proxyPool.release(origins);
		transmitter.close();
		comIdGenerator.detach(id);
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

	private void send(String path, Object[] args, Callback<Object> callback) {
		Long transferKey = transferIdGenerator.next();
		boolean needResponse = callback != null;
		Headers headers = Headers.builder().path(path).id(transferKey).needResponse(needResponse).build();
		Transfer transfer = new Transfer(headers, args);
		if (needResponse) {
			callbacks.put(transferKey, callback);
		}
		this.transmitter.send(transfer);
	}

	private void unboxTransfer(Transfer transfer) {
		Headers headers = transfer.headers;
		Long id = headers.getId();
		if (headers.isResponse()) {
			Callback<Object> callback = callbacks.get(id);
			callback.call(transfer.content);
			callbacks.remove(id);
			transferIdGenerator.detach(id);
		}
		else {
			Object result = invokeEndpoint(headers.getPath(), (Object[]) transfer.content);
			if (headers.needResponse()) {
				this.transmitter.send(new Transfer(Headers.builder().id(id).isResponse(true).build(), result));
			}
		}
	}

	private Object invokeEndpoint(String path, Object[] args) {
		Signature signature = boot.getEndpoint(path);
		if (signature == null) {
			throw new BootException("Endpoint with path '" + path + "' not found");
		}
		Object[] allArgs = resolveArgs(signature.params, args);
		try {
			return signature.method.invoke(endpoints.get(signature.clazz), allArgs);
		}
		catch (IllegalAccessException | InvocationTargetException e) {
			throw new IllegalStateException("Cannot invoke method " + signature.method, e);
		}
		catch (IllegalArgumentException e) {
			throw new BootException("Illegal signature of method '" + signature.method.getName() + "' in " + signature.clazz + ". Expected following parameters " + Arrays
					.stream(allArgs)
					.map(arg -> arg.getClass().getSimpleName())
					.collect(Collectors.joining(",", "[", "]")));
		}
	}

	private Object[] resolveArgs(List<Param> params, Object[] realArgs) {
		Object[] allArgs = new Object[params.size()];
		int raIndex = 0;
		for (int i = 0; i < allArgs.length; i++) {
			Param param = params.get(i);
			allArgs[i] = param.isExtra ? extraArgsResolver.get(param.key).get() : realArgs[raIndex];
		}
		return allArgs;
	}

	private Map<Class<?>, Object> createEndpointInstances(Collection<Class<?>> services) {
		return services.stream()
				.collect(Collectors.toMap(
						service -> service,
						service -> Try.supply(() -> service.getConstructor()
								.newInstance())
								.getOrElseThrow((CheckedFunction<Throwable, IllegalStateException>) IllegalStateException::new)));
	}

	private Map<Class<?>, Object> createOriginProxies() {
		Map<Class<?>, Object> proxies = new HashMap<>();
		OriginHandler originHandler = new OriginHandler();
		for (Class<?> clazz : boot.getOriginServices()) {
			proxies.put(clazz, Proxy.newProxyInstance(clazz.getClassLoader(), new Class[]{clazz}, originHandler));
		}
		return proxies;
	}

	private Map<String, Supplier<Object>> createExtraArgsResolver() {
		return Map.of("client-id", this::id);
	}

	private final class OriginHandler implements InvocationHandler {

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) {
			if (method.getDeclaringClass() == Object.class) {
				throw new UnsupportedOperationException();
			}

			String path = boot.getOrigin(method).path;

			Future<?> future = EXECUTOR.submit(() -> {
				if (args == null) {
					send(path, new Object[0], null);
					return;
				}
				Object lastArg = args[args.length - 1];
				if (lastArg instanceof Callback) {
					@SuppressWarnings("unchecked")
					Callback<Object> callBack = (Callback<Object>) lastArg;
					send(path, getRealArgs(args), callBack);
				}
				else {
					send(path, args, null);
				}
			});
			Threads.handleFutureEx(future);
			return null;
		}

		private Object[] getRealArgs(Object[] args) {
			Object[] realArgs = new Object[args.length - 1];
			System.arraycopy(args, 0, realArgs, 0, realArgs.length);
			return realArgs;
		}
	}

}
