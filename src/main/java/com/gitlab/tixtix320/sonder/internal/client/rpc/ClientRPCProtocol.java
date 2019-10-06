package com.gitlab.tixtix320.sonder.internal.client.rpc;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.gitlab.tixtix320.kiwi.api.check.Try;
import com.gitlab.tixtix320.kiwi.api.observable.Observable;
import com.gitlab.tixtix320.kiwi.api.observable.subject.Subject;
import com.gitlab.tixtix320.kiwi.api.util.IDGenerator;
import com.gitlab.tixtix320.sonder.api.common.communication.Headers;
import com.gitlab.tixtix320.sonder.api.common.communication.Protocol;
import com.gitlab.tixtix320.sonder.api.common.communication.Transfer;
import com.gitlab.tixtix320.sonder.api.common.rpc.extra.ClientID;
import com.gitlab.tixtix320.sonder.internal.common.PathNotFoundException;
import com.gitlab.tixtix320.sonder.internal.common.communication.InvalidHeaderException;
import com.gitlab.tixtix320.sonder.internal.common.rpc.IncompatibleTypeException;
import com.gitlab.tixtix320.sonder.internal.common.rpc.extra.ExtraArg;
import com.gitlab.tixtix320.sonder.internal.common.rpc.extra.ExtraParam;
import com.gitlab.tixtix320.sonder.internal.common.rpc.service.*;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toUnmodifiableMap;


public final class ClientRPCProtocol implements Protocol {

	private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

	private final Map<Class<?>, ?> originServices;

	private final Map<Class<?>, ?> endpointServices;

	private final Map<Method, OriginMethod> originsByMethod;

	private final Map<String, OriginMethod> originsByPath;

	private final Map<String, EndpointMethod> endpointsByPath;

	private final IDGenerator transferIdGenerator;

	private final Map<Long, Subject<Object>> responseSubjects;

	private final Subject<Transfer> requests;

	public ClientRPCProtocol(List<Class<?>> classes) {
		OriginRPCServiceMethods originServiceMethods = new OriginRPCServiceMethods(classes);
		EndpointRPCServiceMethods endpointServiceMethods = new EndpointRPCServiceMethods(classes);

		this.originsByMethod = originServiceMethods.get()
				.stream()
				.collect(toUnmodifiableMap(ServiceMethod::getRawMethod, identity()));

		this.originsByPath = originServiceMethods.get()
				.stream()
				.collect(toUnmodifiableMap(ServiceMethod::getPath, identity()));

		this.endpointsByPath = endpointServiceMethods.get()
				.stream()
				.collect(toUnmodifiableMap(ServiceMethod::getPath, identity()));

		this.originServices = originServiceMethods.get()
				.stream()
				.map(ServiceMethod::getRawClass)
				.distinct()
				.collect(toUnmodifiableMap(clazz -> clazz,
						clazz -> createOriginInstance(clazz, this::handleOriginCall)));

		this.endpointServices = endpointServiceMethods.get()
				.stream()
				.map(ServiceMethod::getRawClass)
				.distinct()
				.collect(toUnmodifiableMap(clazz -> clazz, this::creatEndpointInstance));

		this.responseSubjects = new ConcurrentHashMap<>();
		this.transferIdGenerator = new IDGenerator();

		this.requests = Subject.single();
	}

	@Override
	public void handleTransfer(Transfer transfer) {
		Headers headers = transfer.getHeaders();
		JsonNode contentNode = transfer.getContent();

		Object path = headers.get(Headers.PATH);
		if (!(path instanceof String)) {
			throw new InvalidHeaderException(Headers.PATH, path, String.class);
		}

		Object isResponse = headers.get(Headers.IS_RESPONSE);
		if (isResponse == null || (isResponse instanceof Boolean && !((boolean) isResponse))) {
			EndpointMethod endpointMethod = endpointsByPath.get(path);
			if (endpointMethod == null) {
				throw new PathNotFoundException("Endpoint with path '" + path + "' not found");
			}

			Object sourceClientId = headers.get(Headers.SOURCE_CLIENT_ID);

			if (!(sourceClientId instanceof Number)) {
				throw new InvalidHeaderException(Headers.SOURCE_CLIENT_ID, sourceClientId, Number.class);
			}

			Map<Class<? extends Annotation>, Object> extraArgResolver = new HashMap<>();
			extraArgResolver.put(ClientID.class, ((Number) sourceClientId).longValue());

			Object serviceInstance = endpointServices.get(endpointMethod.getRawClass());

			if (!(contentNode instanceof ArrayNode)) {
				throw new IllegalStateException(String.format("Content must be JSON array, but was %s", contentNode));
			}
			ArrayNode argsNode = (ArrayNode) contentNode;

			List<Param> simpleParams = endpointMethod.getSimpleParams();
			Object[] simpleArgs = new Object[simpleParams.size()];
			for (int i = 0; i < argsNode.size(); i++) {
				JsonNode argNode = argsNode.get(i);
				Param param = simpleParams.get(i);
				simpleArgs[i] = Try.supplyOrRethrow(() -> JSON_MAPPER.treeToValue(argNode, param.getType()));
			}

			Object[] args = appendExtraArgs(simpleArgs, endpointMethod.getExtraParams(),
					annotation -> extraArgResolver.get(annotation.annotationType()));
			Object result = endpointMethod.invoke(serviceInstance, args);

			Object needResponse = headers.get(Headers.NEED_RESPONSE);
			if (needResponse instanceof Boolean && (boolean) needResponse) {
				Headers newHeaders = Headers.builder()
						.header(Headers.PATH, path)
						.header(Headers.TRANSFER_KEY, headers.get(Headers.TRANSFER_KEY))
						.header(Headers.IS_RESPONSE, true)
						.header(Headers.DESTINATION_CLIENT_ID, sourceClientId)
						.build();
				requests.next(new Transfer(newHeaders, JSON_MAPPER.valueToTree(result)));
			}
		}
		else { // is response
			Object transferKey = headers.get(Headers.TRANSFER_KEY);
			if (!(transferKey instanceof Number)) {
				throw new InvalidHeaderException(Headers.TRANSFER_KEY, transferKey, Number.class);
			}
			responseSubjects.computeIfPresent(((Number) transferKey).longValue(), (key, subject) -> {
				OriginMethod originMethod = originsByPath.get(path);
				if (originMethod == null) {
					throw new PathNotFoundException("Origin with path '" + path + "' not found");
				}

				JavaType responseType = originMethod.getResponseType();
				if (responseType.getRawClass() == Void.class) {
					subject.next((Object) null);
				}
				else {
					try {
						Object result = JSON_MAPPER.convertValue(contentNode, responseType);
						subject.next(result);
					}
					catch (IllegalArgumentException e) {
						throw new IncompatibleTypeException(
								String.format("Expected type %s cannot deserialized from JSON %s",
										responseType.getGenericSignature(), contentNode), e);
					}
					catch (ClassCastException e) {
						throw IncompatibleTypeException.forMethodReturnType(originMethod.getRawMethod(), e);
					}
				}
				subject.complete();
				return null;
			});
		}
	}

	@Override
	public Observable<Transfer> transfers() {
		return requests.asObservable();
	}

	@Override
	public String getName() {
		return "sonder-RPC";
	}

	@Override
	public void close() throws IOException {
		requests.complete();
		responseSubjects.values().forEach(Subject::complete);
	}

	@SuppressWarnings("unchecked")
	public <T> T getService(Class<T> clazz) {
		T service = (T) originServices.get(clazz);
		if (service == null) {
			throw new IllegalArgumentException("Service of " + clazz + " not found");
		}
		return service;
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
		Headers.HeadersBuilder builder = Headers.builder()
				.header(Headers.PATH, method.getPath())
				.header(Headers.IS_RESPONSE, false);

		switch (method.getDestination()) {
			case SERVER:
				break;
			case CLIENT:
				ExtraArg extraArg = extraArgs.get(ClientID.class);
				Object clientId = extraArg.getValue();
				builder.header(Headers.DESTINATION_CLIENT_ID, clientId);
				break;
			default:
				throw new IllegalStateException(String.format("Unknown enum type %s", method.getDestination()));
		}

		if (method.needResponse()) {
			long transferKey = transferIdGenerator.next();
			Headers headers = builder.header(Headers.TRANSFER_KEY, transferKey)
					.header(Headers.NEED_RESPONSE, true)
					.build();

			Subject<Object> responseSubject = Subject.single();
			responseSubjects.put(transferKey, responseSubject);
			requests.next(new Transfer(headers, JSON_MAPPER.valueToTree(simpleArgs)));
			return responseSubject.asObservable();
		}
		else {
			Headers headers = builder.header(Headers.NEED_RESPONSE, false).build();
			requests.next(new Transfer(headers, JSON_MAPPER.valueToTree(simpleArgs)));
			return null;
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
}
