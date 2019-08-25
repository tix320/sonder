package io.titix.sonder.client;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitlab.tixtix320.kiwi.check.Try;
import com.gitlab.tixtix320.kiwi.observable.subject.Subject;
import com.gitlab.tixtix320.kiwi.util.IDGenerator;
import io.titix.sonder.client.internal.ClientChannel;
import io.titix.sonder.client.internal.EndpointBoot;
import io.titix.sonder.client.internal.OriginBoot;
import io.titix.sonder.extra.ClientID;
import io.titix.sonder.internal.*;

import static io.titix.sonder.internal.Headers.*;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

/**
 * @author tix32 on 20-Dec-18
 */
@SuppressWarnings("Duplicates")
public final class Client {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final ClientChannel channel;

	private final Map<Class<?>, ?> originServices;

	private final Map<Class<?>, ?> endpointServices;

	private final Map<Method, OriginMethod> originsByMethod;

	private final Map<String, EndpointMethod> endpointsByPath;

	private final IDGenerator transferIdGenerator;

	private final Map<Long, Subject<Object>> resultSubjects;

	public static Client run(String host, int port, List<String> originPackages, List<String> endpointPackages) {
		ClientChannel clientChannel = new ClientChannel(new InetSocketAddress(host, port));
		OriginBoot originBoot = new OriginBoot(Config.getPackageClasses(originPackages));
		EndpointBoot endpointBoot = new EndpointBoot(Config.getPackageClasses(endpointPackages));
		return new Client(clientChannel, originBoot, endpointBoot);
	}

	private Client(ClientChannel channel, OriginBoot originBoot, EndpointBoot endpointBoot) {
		this.channel = channel;

		this.originsByMethod = originBoot.getServiceMethods()
				.stream()
				.collect(toMap(ServiceMethod::getRawMethod, identity()));

		this.endpointsByPath = endpointBoot.getServiceMethods()
				.stream()
				.collect(toMap(ServiceMethod::getPath, identity()));

		this.originServices = originBoot.getServiceMethods()
				.stream()
				.map(ServiceMethod::getRawClass)
				.distinct()
				.collect(toMap(clazz -> clazz, clazz -> createOriginInstance(clazz, this::handleOriginCall)));

		this.endpointServices = endpointBoot.getServiceMethods()
				.stream()
				.map(ServiceMethod::getRawClass)
				.distinct()
				.collect(toMap(clazz -> clazz, this::creatEndpointInstance));

		this.resultSubjects = new ConcurrentHashMap<>();
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

	public void stop() {
		channel.close();
	}

	private Object createOriginInstance(Class<?> clazz, OriginInvocationHandler.Handler invocationHandler) {
		return Proxy.newProxyInstance(clazz.getClassLoader(), new Class[]{clazz},
				new OriginInvocationHandler(originsByMethod::get, invocationHandler));
	}

	private Object creatEndpointInstance(Class<?> clazz) {
		return Try.supplyAndGet(() -> clazz.getConstructor().newInstance());
	}

	private Object handleOriginCall(OriginMethod method, List<Object> simpleArgs,
									Map<Class<? extends Annotation>, ExtraArg> extraArgs) {
		Headers headers = Headers.builder()
				.header(PATH, method.getPath())
				.header(IS_RESPONSE, false)
				.header(SOURCE_CLIENT_ID, channel.getId())
				.build();

		switch (method.destination) {
			case SERVER:
				headers = headers.compose().header(DESTINATION_CLIENT_ID, 0L).build();
				break;
			case CLIENT:
				ExtraArg extraArg = extraArgs.get(ClientID.class);
				Object clientId = extraArg.getValue();
				headers = headers.compose().header(DESTINATION_CLIENT_ID, clientId).build();
				break;
			default:
				throw new IllegalStateException();
		}

		if (method.needResponse) {
			long transferKey = transferIdGenerator.next();
			headers = headers.compose().header(TRANSFER_KEY, transferKey).header(NEED_RESPONSE, true).build();
			Transfer transfer = new Transfer<>(headers, simpleArgs.toArray());

			Subject<Object> resultSubject = Subject.single();
			resultSubjects.put(transferKey, resultSubject);

			CompletableFuture.runAsync(() -> this.channel.send(serialize(transfer)))
					.whenCompleteAsync((v, throwable) -> Optional.ofNullable(throwable)
							.ifPresent(t -> t.getCause().printStackTrace()));

			return resultSubject.asObservable().one();
		}
		else {
			headers = headers.compose().header(NEED_RESPONSE, false).build();

			Transfer transfer = new Transfer<>(headers, simpleArgs.toArray());

			CompletableFuture.runAsync(() -> this.channel.send(serialize(transfer)))
					.whenCompleteAsync((v, throwable) -> Optional.ofNullable(throwable)
							.ifPresent(t -> t.getCause().printStackTrace()));
			return null;
		}
	}

	private void handleIncomingTransfers() {
		channel.requests().subscribe(data -> {
			ObjectNode transfer = (ObjectNode) deserialize(data);
			Headers headers = toHeaders(transfer.get("headers"));
			String path = headers.getString(PATH);

			EndpointMethod method = endpointsByPath.get(path);
			if (headers.getBoolean(IS_RESPONSE)) {
				Long transferKey = headers.getLong(TRANSFER_KEY);
				resultSubjects.computeIfPresent(transferKey, (key, subject) -> {
					//try {
					JsonNode content = transfer.get("content");
					Class<?> returnType = method.getRawMethod().getReturnType();
					Object result = deserialize(content, returnType);
					subject.next(result);
					subject.complete();
					//}
					//catch (ClassCastException e) {
					//throw new IllegalStateException(String.format(
					//		"Origin method %s(%s) return type is not compatible with received response type(%s)",
					//		method.getRawMethod().getName(), method.getRawClass(), object.getClass()));
					//}
					return null;
				});
			}
			else {
				if (method == null) {
					throw new PathNotFoundException("Endpoint with path '" + path + "' not found");
				}

				Long sourceClientId = headers.getLong(SOURCE_CLIENT_ID);

				Map<Class<? extends Annotation>, Object> extraArgResolver = new HashMap<>();
				extraArgResolver.put(ClientID.class, sourceClientId);

				Object serviceInstance = endpointServices.get(method.getRawClass());

				ArrayNode argsNode = transfer.withArray("content");

				List<Param> simpleParams = method.getSimpleParams();
				Object[] simpleArgs = new Object[simpleParams.size()];
				for (int i = 0; i < argsNode.size(); i++) {
					JsonNode argNode = argsNode.get(i);
					Param param = simpleParams.get(i);
					simpleArgs[i] = deserialize(argNode, param.getType());
				}

				Object[] args = appendExtraArgs(simpleArgs, method.getExtraParams(),
						annotation -> extraArgResolver.get(annotation.annotationType()));
				Object result = method.invoke(serviceInstance, args);

				if (headers.getBoolean(NEED_RESPONSE)) {
					Headers newHeaders = Headers.builder()
							.header(TRANSFER_KEY, headers.getLong(TRANSFER_KEY))
							.header(IS_RESPONSE, true)
							.header(DESTINATION_CLIENT_ID, sourceClientId)
							.build();
					this.channel.send(serialize(new Transfer<>(newHeaders, result)));
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

	private static byte[] serialize(Transfer obj) {
		ObjectMapper mapper = new ObjectMapper();
		try {
			return mapper.writeValueAsBytes(obj);
		}
		catch (JsonProcessingException e) {
			throw new IllegalStateException(e);
		}
	}

	private static JsonNode deserialize(byte[] data) {
		ObjectMapper mapper = new ObjectMapper();
		try {
			return mapper.readTree(data);
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private static Object deserialize(JsonNode node, Class<?> requiredType) {
		try {
			return MAPPER.treeToValue(node, requiredType);
		}
		catch (JsonProcessingException e) {
			throw new RuntimeException(e);
		}
	}

	private static Headers toHeaders(JsonNode jsonNode) {
		try {
			return MAPPER.treeToValue(jsonNode, Headers.class);
		}
		catch (JsonProcessingException e) {
			throw new RuntimeException(e);
		}
	}
}
