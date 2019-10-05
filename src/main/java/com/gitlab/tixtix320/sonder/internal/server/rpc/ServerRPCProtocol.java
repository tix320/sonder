package com.gitlab.tixtix320.sonder.internal.server.rpc;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

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
import com.gitlab.tixtix320.sonder.internal.common.StartupException;
import com.gitlab.tixtix320.sonder.internal.common.communication.InvalidHeaderException;
import com.gitlab.tixtix320.sonder.internal.common.rpc.extra.ExtraArg;
import com.gitlab.tixtix320.sonder.internal.common.rpc.extra.ExtraParam;
import com.gitlab.tixtix320.sonder.internal.common.rpc.service.*;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toUnmodifiableMap;

public final class ServerRPCProtocol implements Protocol {

	private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

	private final Map<Class<?>, ?> originServices;

	private final Map<Class<?>, Object> endpointServices;

	private final Map<Method, OriginMethod> originsByMethod;

	private final Map<String, OriginMethod> originsByPath;

	private final Map<String, EndpointMethod> endpointsByPath;

	private final Map<Long, Subject<Object>> responseSubjects;

	private final IDGenerator transferIdGenerator;

	private final Subject<Transfer> requests;

	public ServerRPCProtocol(List<Class<?>> classes) {
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
				.peek(ServerRPCProtocol::checkDestination)
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

		Object destinationClientId = headers.get(Headers.DESTINATION_CLIENT_ID);
		if (destinationClientId == null) { // for server
			Object path = headers.get(Headers.PATH);
			if (!(path instanceof String)) {
				throw new InvalidHeaderException(Headers.PATH, path, String.class);
			}
			EndpointMethod method = endpointsByPath.get(path);
			if (method == null) {
				throw new PathNotFoundException("Endpoint with path '" + path + "' not found");
			}

			Object isResponse = headers.get(Headers.IS_RESPONSE);
			if (isResponse == null || (isResponse instanceof Boolean && !((boolean) isResponse))) {
				Object sourceClientId = headers.get(Headers.SOURCE_CLIENT_ID);
				if (!(sourceClientId instanceof Number)) {
					throw new InvalidHeaderException(Headers.SOURCE_CLIENT_ID, sourceClientId, Number.class);
				}
				Map<Class<? extends Annotation>, Object> extraArgResolver = Map.of(ClientID.class,
						((Number) sourceClientId).longValue());

				Object serviceInstance = endpointServices.get(method.getRawClass());
				if (!(contentNode instanceof ArrayNode)) {
					throw new IllegalStateException(
							String.format("Content must be JSON array, but was %s", contentNode));
				}
				ArrayNode argsNode = (ArrayNode) contentNode;

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
				Object needResponse = headers.get(Headers.NEED_RESPONSE);
				if (needResponse instanceof Boolean && (boolean) needResponse) {
					Headers newHeaders = Headers.builder()
							.header(Headers.DESTINATION_CLIENT_ID, sourceClientId)
							.header(Headers.TRANSFER_KEY, headers.get(Headers.TRANSFER_KEY))
							.header(Headers.IS_RESPONSE, true)
							.build();
					requests.next(new Transfer(newHeaders, JSON_MAPPER.valueToTree(object)));
				}
			}
			else { // is response
				Object transferKey = headers.get(Headers.TRANSFER_KEY);
				if (!(transferKey instanceof Number)) {
					throw new InvalidHeaderException(Headers.TRANSFER_KEY, transferKey, Number.class);
				}
				responseSubjects.computeIfPresent(((Number) transferKey).longValue(), (key, subject) -> {
					Class<?> returnType = method.getRawMethod().getReturnType();
					Object result = Try.supplyOrRethrow(() -> JSON_MAPPER.treeToValue(contentNode, returnType));
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
							throw new IllegalStateException(String.format(
									"Origin method %s(%s) return type is %s, which is not compatible with received response type(%s)",
									originMethod.getRawMethod().getName(), originMethod.getRawClass(),
									actualReturnTypeName, result.getClass()), e);
						}
					}
					subject.complete();
					return null;
				});
			}
		}
		else { // for any client
			requests.next(transfer);
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

	private static void checkDestination(OriginMethod signature) {
		if (signature.getDestination() == OriginMethod.Destination.SERVER) {
			throw new StartupException(String.format(
					"In Server environment origin method '%s' in '%s' must have parameter annotated by @'%s'",
					signature.getRawMethod().getName(), signature.getRawClass(), ClientID.class.getSimpleName()));
		}
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
		long clientId = (long) extraArgs.get(ClientID.class).getValue();

		Headers.HeadersBuilder builder = Headers.builder()
				.header(Headers.PATH, method.getPath())
				.header(Headers.DESTINATION_CLIENT_ID, clientId);

		if (method.needResponse()) {
			Long transferKey = transferIdGenerator.next();
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
