package io.titix.sonder.internal;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import io.titix.kiwi.check.CheckedFunction;
import io.titix.kiwi.check.Try;
import io.titix.kiwi.util.IdGenerator;
import io.titix.kiwi.util.Pool;
import io.titix.kiwi.util.Threads;

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

	private final Map<Long, SynchronousQueue<Object>> exchangers;

	private final IdGenerator transferIdGenerator;

	private final Map<String, Supplier<Object>> extraArgsResolver;

	public Communicator(Socket socket, Boot boot) {
		this.id = comIdGenerator.next();
		this.boot = boot;
		this.extraArgsResolver = createExtraArgsResolver();
		this.transmitter = new Transmitter(socket);
		this.exchangers = new ConcurrentHashMap<>();
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

	private CompletableFuture<Object> send(String path, Object[] args, boolean needResponse) {
		Long transferKey = transferIdGenerator.next();

		Headers headers = Headers.builder().path(path).id(transferKey).needResponse(needResponse).build();
		Transfer transfer = new Transfer(headers, args);

		CompletableFuture<Object> result;
		if (needResponse) {
			SynchronousQueue<Object> exchanger = new SynchronousQueue<>();
			result = CompletableFuture.supplyAsync(() -> Try.supplyChecked(exchanger::take, IllegalStateException::new), EXECUTOR);
			exchangers.put(transferKey, exchanger);
		}
		else {
			result = CompletableFuture.completedFuture(null);
		}
		this.transmitter.send(transfer);
		return result;
	}

	private void unboxTransfer(Transfer transfer) {
		Headers headers = transfer.headers;
		Long id = headers.getId();
		if (headers.isResponse()) {
			SynchronousQueue<Object> exchanger = exchangers.get(id);
			Try.runChecked(() -> exchanger.put(transfer.content), IllegalStateException::new);

			exchangers.remove(id);
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
		Object[] allArgs = resolveArgs(signature, args);
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

	private Object[] resolveArgs(Signature signature, Object[] realArgs) {
		if (signature.params.stream().filter(param -> !param.isExtra).count() != realArgs.length) {
			throw new BootException("Illegal signature of method '" + signature.method.getName() + "' in " + signature.clazz + ". Expected following parameters "
					+ Arrays.stream(realArgs)
					.map(arg -> arg.getClass().getSimpleName())
					.collect(Collectors.joining(",", "[", "]")));
		}
		Object[] allArgs = new Object[signature.params.size()];
		int raIndex = 0;
		for (int i = 0; i < allArgs.length; i++) {
			Param param = signature.params.get(i);
			allArgs[i] = param.isExtra ? extraArgsResolver.get(param.key).get() : realArgs[raIndex++];
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

			boolean needResponse = method.getReturnType() == CompletableFuture.class;

			if (args == null) {
				args = new Object[0];
			}

			if (needResponse) {
				return send(path, args, true);
			}
			else {
				send(path, args, false);
				return null;
			}
		}
	}

}
