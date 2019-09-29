package com.gitlab.tixtix320.sonder.api.server;

import java.io.Closeable;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitlab.tixtix320.kiwi.api.check.Try;
import com.gitlab.tixtix320.kiwi.api.observable.subject.Subject;
import com.gitlab.tixtix320.kiwi.api.util.IDGenerator;
import com.gitlab.tixtix320.sonder.api.common.extra.ClientID;
import com.gitlab.tixtix320.sonder.internal.common.PathNotFoundException;
import com.gitlab.tixtix320.sonder.internal.common.StartupException;
import com.gitlab.tixtix320.sonder.internal.common.communication.Headers;
import com.gitlab.tixtix320.sonder.internal.common.communication.Transfer;
import com.gitlab.tixtix320.sonder.internal.common.extra.ExtraParam;
import com.gitlab.tixtix320.sonder.internal.common.service.*;
import com.gitlab.tixtix320.sonder.internal.common.util.ClassFinder;
import com.gitlab.tixtix320.sonder.internal.server.ClientsSelector;
import com.gitlab.tixtix320.sonder.internal.server.EndpointServiceMethods;
import com.gitlab.tixtix320.sonder.internal.server.OriginServiceMethods;
import com.gitlab.tixtix320.sonder.internal.server.SocketClientsSelector;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

/**
 * @author Tigran.Sargsyan on 11-Dec-18
 */
@SuppressWarnings("Duplicates")
public final class Sonder implements Closeable {

	private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

	private final ClientsSelector clientsSelector;

	private final Map<Class<?>, ?> originServices;

	private final Map<Class<?>, ?> endpointServices;

	private final Map<Method, OriginMethod> originsByMethod;

	private final Map<String, EndpointMethod> endpointsByPath;

	private final Map<Long, Subject<Object>> resultSubjects;

	private final IDGenerator transferIdGenerator;

	public static Sonder run(int port, List<String> originPackages, List<String> endpointPackages) {
		OriginServiceMethods originBoot = new OriginServiceMethods(ClassFinder.getPackageClasses(originPackages));
		EndpointServiceMethods endpointBoot = new EndpointServiceMethods(
				ClassFinder.getPackageClasses(endpointPackages));

		return new Sonder(new SocketClientsSelector(new InetSocketAddress(port)), originBoot, endpointBoot);
	}

	private Sonder(ClientsSelector clientsSelector, ServiceMethods<OriginMethod> originServiceMethods,
				   ServiceMethods<EndpointMethod> endpointServiceMethods) {
		this.clientsSelector = clientsSelector;
		clientsSelector.requests().subscribe(this::handleTransfer);

		this.originsByMethod = originServiceMethods.get()
				.stream()
				.collect(toMap(ServiceMethod::getRawMethod, identity()));

		this.endpointsByPath = endpointServiceMethods.get().stream().collect(toMap(ServiceMethod::getPath, identity()));

		OriginInvocationHandler.Handler invocationHandler = createOriginInvocationHandler();
		this.originServices = originServiceMethods.get()
				.stream().peek(Sonder::checkDestination)
				.map(ServiceMethod::getRawClass)
				.distinct()
				.collect(toMap(clazz -> clazz, clazz -> createOriginInstance(clazz, invocationHandler)));

		this.endpointServices = endpointServiceMethods.get()
				.stream()
				.map(ServiceMethod::getRawClass)
				.distinct()
				.collect(toMap(clazz -> clazz, this::creatEndpointInstance));

		this.resultSubjects = new ConcurrentHashMap<>();
		this.transferIdGenerator = new IDGenerator();
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
		clientsSelector.close();
	}

	private void handleTransfer(ClientsSelector.Result result) {
		ObjectNode transfer = (ObjectNode) Try.supplyOrRethrow(() -> JSON_MAPPER.readTree(result.getData()));
		JsonNode headersNode = transfer.get("headers");
		if (headersNode == null) {
			new IllegalStateException("Headers not provided").printStackTrace();
			return;
		}
		Headers headers = Try.supplyOrRethrow(() -> JSON_MAPPER.treeToValue(headersNode, Headers.class));

		Long destinationClientId = headers.getLong(Headers.DESTINATION_CLIENT_ID);
		if (destinationClientId == null) {
			throw new IllegalStateException("Destination client id not provided");
		}
		if (destinationClientId != 0L) { // for any client
			JsonNode content = transfer.get("content");
			if (content == null) {
				throw new IllegalStateException("Content not provided");
			}
			ObjectNode transferNode = new ObjectNode(JsonNodeFactory.instance);
			transferNode.set("headers", headersNode);
			transferNode.set("content", content);

			clientsSelector.send(destinationClientId,
					Try.supplyOrRethrow(() -> JSON_MAPPER.writeValueAsString(transferNode).getBytes()));
		}
		else { // for server
			String path = headers.getString(Headers.PATH);
			EndpointMethod method = endpointsByPath.get(path);
			if (method == null) {
				throw new PathNotFoundException("Endpoint with path '" + path + "' not found");
			}

			Boolean isResponse = headers.getBoolean(Headers.IS_RESPONSE);
			if (isResponse) {
				Long transferKey = headers.getLong(Headers.TRANSFER_KEY);
				Subject<Object> subject = resultSubjects.get(transferKey);
				if (subject == null) {
					throw new IllegalStateException(String.format("Transfer key %s is invalid", transferKey));
				}
				JsonNode content = transfer.get("content");
				if (content == null) {
					throw new IllegalStateException("Content not provided");
				}
				Class<?> returnType = method.getRawMethod().getReturnType();
				Object object = Try.supplyOrRethrow(() -> JSON_MAPPER.treeToValue(content, returnType));
				subject.next(object);
				resultSubjects.remove(transferKey);
			}
			else {
				Long sourceClientId = headers.getLong(Headers.SOURCE_CLIENT_ID);
				if (sourceClientId == null) {
					throw new IllegalStateException("Source client id not provided");
				}
				Map<Class<? extends Annotation>, Object> extraArgResolver = Map.of(ClientID.class, sourceClientId);

				Object serviceInstance = endpointServices.get(method.getRawClass());
				ArrayNode argsNode = transfer.withArray("content");
				if (argsNode == null) {
					throw new IllegalStateException("Arguments not provided");

				}

				List<Param> simpleParams = method.getSimpleParams();
				Object[] simpleArgs = new Object[simpleParams.size()];
				for (int i = 0; i < argsNode.size(); i++) {
					JsonNode argNode = argsNode.get(i);
					Param param = simpleParams.get(i);
					simpleArgs[i] = Try.supplyOrRethrow(() -> JSON_MAPPER.treeToValue(argNode, param.getType()));
				}
				Object[] args = appendExtraArgs(simpleArgs, method.getExtraParams(),
						annotation -> extraArgResolver.get(annotation.annotationType()));
				Object object = method.invoke(serviceInstance, args);
				Boolean needResponse = headers.getBoolean(Headers.NEED_RESPONSE);
				if (needResponse) {
					Headers newHeaders = Headers.builder()
							.header(Headers.DESTINATION_CLIENT_ID, sourceClientId)
							.header(Headers.TRANSFER_KEY, headers.getLong(Headers.TRANSFER_KEY))
							.header(Headers.IS_RESPONSE, true)
							.build();
					clientsSelector.send(sourceClientId, Try.supplyOrRethrow(
							() -> JSON_MAPPER.writeValueAsBytes(new Transfer<>(newHeaders, object))));
				}
			}
		}
	}

	private static void checkDestination(OriginMethod signature) {
		if (signature.getDestination() == OriginMethod.Destination.SERVER) {
			throw new StartupException(String.format(
					"In Server environment origin method '%s' in '%s' must have parameter annotated by @'%s'",
					signature.getRawMethod().getName(), signature.getRawClass(), ClientID.class.getSimpleName()));
		}
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

	private Object createOriginInstance(Class<?> clazz, OriginInvocationHandler.Handler invocationHandler) {
		return Proxy.newProxyInstance(clazz.getClassLoader(), new Class[]{clazz},
				new OriginInvocationHandler(originsByMethod::get, invocationHandler));
	}

	private Object creatEndpointInstance(Class<?> clazz) {
		return Try.supplyOrRethrow(() -> clazz.getConstructor().newInstance());
	}

	private OriginInvocationHandler.Handler createOriginInvocationHandler() {
		return (method, simpleArgs, extraArgs) -> {
			long clientId = (long) extraArgs.get(ClientID.class).getValue();

			if (method.needResponse()) {
				Long transferKey = transferIdGenerator.next();
				Headers headers = Headers.builder()
						.header(Headers.PATH, method.getPath())
						.header(Headers.DESTINATION_CLIENT_ID, clientId)
						.header(Headers.TRANSFER_KEY, transferKey)
						.header(Headers.IS_RESPONSE, false)
						.header(Headers.NEED_RESPONSE, true)
						.build();
				Subject<Object> resultSubject = Subject.single();
				resultSubjects.put(transferKey, resultSubject);
				clientsSelector.send(headers.getLong(Headers.DESTINATION_CLIENT_ID), Try.supplyOrRethrow(
						() -> JSON_MAPPER.writeValueAsBytes(new Transfer<>(headers, simpleArgs.toArray()))));
				return resultSubject.asObservable().one();
			}
			else {
				Headers headers = Headers.builder()
						.header(Headers.PATH, method.getPath())
						.header(Headers.NEED_RESPONSE, false)
						.header(Headers.IS_RESPONSE, false)
						.build();
				clientsSelector.send(headers.getLong(Headers.DESTINATION_CLIENT_ID),
						Try.supplyOrRethrow(() -> JSON_MAPPER.writeValueAsBytes(new Transfer<>(headers, simpleArgs))));
				return null;
			}
		};
	}
}
