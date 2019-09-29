package com.gitlab.tixtix320.sonder.api.client;

import java.io.Closeable;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitlab.tixtix320.kiwi.api.check.Try;
import com.gitlab.tixtix320.kiwi.api.observable.subject.Subject;
import com.gitlab.tixtix320.kiwi.api.util.IDGenerator;
import com.gitlab.tixtix320.sonder.api.common.extra.ClientID;
import com.gitlab.tixtix320.sonder.internal.client.EndpointServiceMethods;
import com.gitlab.tixtix320.sonder.internal.client.OriginServiceMethods;
import com.gitlab.tixtix320.sonder.internal.client.ServerConnection;
import com.gitlab.tixtix320.sonder.internal.client.SocketServerConnection;
import com.gitlab.tixtix320.sonder.internal.common.PathNotFoundException;
import com.gitlab.tixtix320.sonder.internal.common.communication.Headers;
import com.gitlab.tixtix320.sonder.internal.common.communication.Transfer;
import com.gitlab.tixtix320.sonder.internal.common.extra.ExtraArg;
import com.gitlab.tixtix320.sonder.internal.common.extra.ExtraParam;
import com.gitlab.tixtix320.sonder.internal.common.service.*;
import com.gitlab.tixtix320.sonder.internal.common.util.ClassFinder;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

/**
 * @author tix32 on 20-Dec-18
 */
@SuppressWarnings("Duplicates")
public final class Clonder implements Closeable {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final ServerConnection connection;

	private final Map<Class<?>, ?> originServices;

	private final Map<Class<?>, ?> endpointServices;

	private final Map<Method, OriginMethod> originsByMethod;

	private final Map<String, OriginMethod> originsByPath;

	private final Map<String, EndpointMethod> endpointsByPath;

	private final IDGenerator transferIdGenerator;

	private final Map<Long, Subject<Object>> responseSubjects;

	public static Clonder run(String host, int port, List<String> originPackages, List<String> endpointPackages) {
		SocketServerConnection socketServerConnection = new SocketServerConnection(new InetSocketAddress(host, port));
		OriginServiceMethods originBoot = new OriginServiceMethods(ClassFinder.getPackageClasses(originPackages));
		EndpointServiceMethods endpointBoot = new EndpointServiceMethods(
				ClassFinder.getPackageClasses(endpointPackages));
		return new Clonder(socketServerConnection, originBoot, endpointBoot);
	}

	private Clonder(ServerConnection connection, OriginServiceMethods originBoot, EndpointServiceMethods endpointBoot) {
		this.connection = connection;

		this.originsByMethod = originBoot.get().stream().collect(toMap(ServiceMethod::getRawMethod, identity()));

		this.originsByPath = originBoot.get().stream().collect(toMap(ServiceMethod::getPath, identity()));

		this.endpointsByPath = endpointBoot.get().stream().collect(toMap(ServiceMethod::getPath, identity()));

		this.originServices = originBoot.get()
				.stream()
				.map(ServiceMethod::getRawClass)
				.distinct()
				.collect(toMap(clazz -> clazz, clazz -> createOriginInstance(clazz, this::handleOriginCall)));

		this.endpointServices = endpointBoot.get()
				.stream()
				.map(ServiceMethod::getRawClass)
				.distinct()
				.collect(toMap(clazz -> clazz, this::creatEndpointInstance));

		this.responseSubjects = new ConcurrentHashMap<>();
		this.transferIdGenerator = new IDGenerator();

		handleIncomingTransfers();
	}

	@SuppressWarnings("unchecked")
	public <T> T getService(Class<T> clazz) {
		T service = (T) originServices.get(clazz);
		if (service == null) {
			throw new IllegalArgumentException("Service of " + clazz + " not found");
		}
		return service;
	}

	@Override
	public void close() throws IOException {
		connection.close();
	}

	private Object createOriginInstance(Class<?> clazz, OriginInvocationHandler.Handler invocationHandler) {
		return Proxy.newProxyInstance(clazz.getClassLoader(), new Class[]{clazz},
				new OriginInvocationHandler(originsByMethod::get, invocationHandler));
	}

	private Object creatEndpointInstance(Class<?> clazz) {
		return Try.supplyOrRethrow(() -> clazz.getConstructor().newInstance());
	}

	private Object handleOriginCall(OriginMethod method, List<Object> simpleArgs,
									Map<Class<? extends Annotation>, ExtraArg> extraArgs) {
		Headers headers = Headers.builder()
				.header(Headers.PATH, method.getPath())
				.header(Headers.IS_RESPONSE, false)
				.build();

		switch (method.getDestination()) {
			case SERVER:
				headers = headers.compose().header(Headers.DESTINATION_CLIENT_ID, 0L).build();
				break;
			case CLIENT:
				ExtraArg extraArg = extraArgs.get(ClientID.class);
				Object clientId = extraArg.getValue();
				headers = headers.compose().header(Headers.DESTINATION_CLIENT_ID, clientId).build();
				break;
			default:
				throw new IllegalStateException();
		}

		if (method.needResponse()) {
			long transferKey = transferIdGenerator.next();
			headers = headers.compose()
					.header(Headers.TRANSFER_KEY, transferKey)
					.header(Headers.NEED_RESPONSE, true)
					.build();
			Transfer transfer = new Transfer<>(headers, simpleArgs.toArray());

			Subject<Object> resultSubject = Subject.single();
			responseSubjects.put(transferKey, resultSubject);

			CompletableFuture.runAsync(
					() -> this.connection.send(Try.supplyOrRethrow(() -> MAPPER.writeValueAsBytes((transfer)))))
					.exceptionally(throwable -> {
						throwable.getCause().printStackTrace();
						return null;
					});

			return resultSubject.asObservable().one();
		}
		else {
			headers = headers.compose().header(Headers.NEED_RESPONSE, false).build();

			Transfer transfer = new Transfer<>(headers, simpleArgs.toArray());

			CompletableFuture.runAsync(
					() -> this.connection.send(Try.supplyOrRethrow(() -> MAPPER.writeValueAsBytes((transfer)))))
					.exceptionally(throwable -> {
						throwable.getCause().printStackTrace();
						return null;
					});
			return null;
		}
	}

	private void handleIncomingTransfers() {
		connection.requests().subscribe(data -> {
			ObjectNode transfer = (ObjectNode) Try.supplyOrRethrow(() -> MAPPER.readTree(data));
			Headers headers = Try.supplyOrRethrow(() -> MAPPER.treeToValue(transfer.get("headers"), Headers.class));
			String path = headers.getString(Headers.PATH);

			EndpointMethod method = endpointsByPath.get(path);
			if (method == null) {
				throw new PathNotFoundException("Origin with path '" + path + "' not found");
			}
			if (headers.getBoolean(Headers.IS_RESPONSE)) {
				Long transferKey = headers.getLong(Headers.TRANSFER_KEY);
				responseSubjects.computeIfPresent(transferKey, (key, subject) -> {
					JsonNode content = transfer.get("content");
					Class<?> returnType = method.getRawMethod().getReturnType();
					Object result = Try.supplyOrRethrow(() -> MAPPER.treeToValue(content, returnType));
					OriginMethod originMethod = originsByPath.get(path);
					String actualReturnTypeName = ((ParameterizedType) originMethod.getRawMethod()
							.getGenericReturnType()).getActualTypeArguments()[0].getTypeName();
					if (Try.supplyOrRethrow(() -> Class.forName(actualReturnTypeName)) == Void.class) {
						subject.next((Object) null);
					}
					else {
						try {
							subject.next(result);
						}
						catch (ClassCastException e) {
							new IllegalStateException(String.format(
									"Origin method %s(%s) return type is %s, which is not compatible with received response type(%s)",
									originMethod.getRawMethod().getName(), originMethod.getRawClass(),
									actualReturnTypeName, result.getClass()), e).printStackTrace();
						}
					}
					subject.complete();
					return null;
				});
			}
			else {
				Long sourceClientId = headers.getLong(Headers.SOURCE_CLIENT_ID);

				Map<Class<? extends Annotation>, Object> extraArgResolver = new HashMap<>();
				extraArgResolver.put(ClientID.class, sourceClientId);

				Object serviceInstance = endpointServices.get(method.getRawClass());

				ArrayNode argsNode = transfer.withArray("content");

				List<Param> simpleParams = method.getSimpleParams();
				Object[] simpleArgs = new Object[simpleParams.size()];
				for (int i = 0; i < argsNode.size(); i++) {
					JsonNode argNode = argsNode.get(i);
					Param param = simpleParams.get(i);
					simpleArgs[i] = Try.supplyOrRethrow(() -> MAPPER.treeToValue(argNode, param.getType()));
				}

				Object[] args = appendExtraArgs(simpleArgs, method.getExtraParams(),
						annotation -> extraArgResolver.get(annotation.annotationType()));
				Object result = method.invoke(serviceInstance, args);

				if (headers.getBoolean(Headers.NEED_RESPONSE)) {
					Headers newHeaders = Headers.builder().header(Headers.PATH, path)
							.header(Headers.TRANSFER_KEY, headers.getLong(Headers.TRANSFER_KEY))
							.header(Headers.IS_RESPONSE, true)
							.header(Headers.DESTINATION_CLIENT_ID, sourceClientId)
							.build();
					this.connection.send(
							Try.supplyOrRethrow(() -> MAPPER.writeValueAsBytes(new Transfer<>(newHeaders, result))));
				}
			}
		});
	}

	private Object[] appendExtraArgs(Object[] simpleArgs, List<ExtraParam> extraParams,
									 Function<Annotation, Object> extraArgResolver) {

		Object[] allArgs = new Object[simpleArgs.length + extraParams.size()];

		System.arraycopy(simpleArgs, 0, allArgs, 0, simpleArgs.length); // fill simple args

		for (ExtraParam extraParam : extraParams) {
			allArgs[extraParam.getIndex()] = extraArgResolver.apply(extraParam.getAnnotation());
		}

		return allArgs;
	}
}
