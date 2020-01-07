package com.gitlab.tixtix320.sonder.internal.client.rpc;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.gitlab.tixtix320.kiwi.api.check.Try;
import com.gitlab.tixtix320.kiwi.api.observable.Observable;
import com.gitlab.tixtix320.kiwi.api.observable.subject.Subject;
import com.gitlab.tixtix320.kiwi.api.util.IDGenerator;
import com.gitlab.tixtix320.kiwi.api.util.None;
import com.gitlab.tixtix320.sonder.api.common.communication.*;
import com.gitlab.tixtix320.sonder.api.common.communication.Headers.HeadersBuilder;
import com.gitlab.tixtix320.sonder.api.common.rpc.extra.ClientID;
import com.gitlab.tixtix320.sonder.internal.common.communication.BuiltInProtocol;
import com.gitlab.tixtix320.sonder.internal.common.communication.UnsupportedContentTypeException;
import com.gitlab.tixtix320.sonder.internal.common.rpc.IncompatibleTypeException;
import com.gitlab.tixtix320.sonder.internal.common.rpc.PathNotFoundException;
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

	private final Subject<Transfer> outgoingRequests;

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

		this.outgoingRequests = Subject.single();
	}

	@Override
	public void handleIncomingTransfer(Transfer transfer)
			throws IOException {
		Headers headers = transfer.getHeaders();

		Boolean isInvoke = headers.getBoolean(Headers.IS_INVOKE);
		if (isInvoke != null && isInvoke) {
			processInvocation(transfer);
		}
		else {
			processResult(transfer);
		}
	}

	@Override
	public Observable<Transfer> outgoingTransfers() {
		return outgoingRequests.asObservable();
	}

	@Override
	public String getName() {
		return BuiltInProtocol.RPC.getName();
	}

	@Override
	public void close() {
		outgoingRequests.complete();
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
				.header(Headers.IS_INVOKE, true);

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

		byte[] content;
		if (method.isBinaryInvocation()) {
			builder.contentType(ContentType.BINARY);
			content = (byte[]) simpleArgs.get(0);
		}
		else {
			builder.contentType(ContentType.JSON);
			content = Try.supplyOrRethrow(() -> JSON_MAPPER.writeValueAsBytes(simpleArgs));
		}

		if (method.needResponse()) {
			long transferKey = transferIdGenerator.next();
			Headers headers = builder.header(Headers.TRANSFER_KEY, transferKey)
					.header(Headers.NEED_RESPONSE, true)
					.build();

			Subject<Object> responseSubject = Subject.single();
			responseSubjects.put(transferKey, responseSubject);
			outgoingRequests.next(new StaticTransfer(headers, content));
			return responseSubject.asObservable();
		}
		else {
			Headers headers = builder.header(Headers.NEED_RESPONSE, false).build();
			outgoingRequests.next(new StaticTransfer(headers, content));
			return null;
		}
	}

	private void processInvocation(Transfer transfer)
			throws IOException {
		Headers headers = transfer.getHeaders();
		byte[] content = transfer.readAll();

		String path = headers.getNonNullString(Headers.PATH);

		EndpointMethod endpointMethod = endpointsByPath.get(path);
		if (endpointMethod == null) {
			throw new PathNotFoundException("Endpoint with path '" + path + "' not found");
		}

		Number sourceClientId = headers.getNumber(Headers.SOURCE_CLIENT_ID);

		List<Param> simpleParams = endpointMethod.getSimpleParams();
		Object[] simpleArgs = new Object[simpleParams.size()];

		ContentType contentType = headers.getContentType();

		switch (contentType) {
			case BINARY:
				if (simpleArgs.length != 1) {
					throw new IllegalStateException(String.format(
							"The request type is byte[]. Consequently endpoint method %s(%s) must have only one parameter with type byte[]",
							endpointMethod.getRawMethod().getName(), endpointMethod.getRawClass().getName()));
				}
				simpleArgs[0] = content;
				break;
			case JSON:
				ArrayNode argsNode;
				try {
					argsNode = JSON_MAPPER.readValue(content, ArrayNode.class);
				}
				catch (IOException e) {
					throw new IllegalStateException(e);
				}
				for (int i = 0; i < argsNode.size(); i++) {
					JsonNode argNode = argsNode.get(i);
					Param param = simpleParams.get(i);
					simpleArgs[i] = Try.supplyOrRethrow(() -> JSON_MAPPER.convertValue(argNode, param.getType()));
				}
				break;
			default:
				throw new UnsupportedContentTypeException(contentType);
		}

		Map<Class<? extends Annotation>, Object> extraArgs = new HashMap<>();
		extraArgs.put(ClientID.class, sourceClientId);

		Object serviceInstance = endpointServices.get(endpointMethod.getRawClass());
		Object[] args = appendExtraArgs(simpleArgs, endpointMethod.getExtraParams(), extraArgs);
		Object result = endpointMethod.invoke(serviceInstance, args);

		HeadersBuilder builder = Headers.builder();
		byte[] transferContent;
		if (endpointMethod.isBinaryResult()) {
			builder.contentType(ContentType.BINARY);
			transferContent = (byte[]) result;
		}
		else {
			builder.contentType(ContentType.JSON);
			transferContent = Try.supplyOrRethrow(() -> JSON_MAPPER.writeValueAsBytes(result));
		}

		Boolean needResponse = headers.getBoolean(Headers.NEED_RESPONSE);
		if (needResponse != null && needResponse) {
			Headers newHeaders = builder.header(Headers.PATH, path)
					.header(Headers.TRANSFER_KEY, headers.get(Headers.TRANSFER_KEY))
					.header(Headers.DESTINATION_CLIENT_ID, sourceClientId)
					.build();
			outgoingRequests.next(new StaticTransfer(newHeaders, transferContent));
		}
	}

	private void processResult(Transfer transfer)
			throws IOException {
		final Headers headers = transfer.getHeaders();
		final byte[] content = transfer.readAll();

		String path = headers.getNonNullString(Headers.PATH);

		Number transferKey = headers.getNonNullNumber(Headers.TRANSFER_KEY);

		responseSubjects.computeIfPresent(transferKey.longValue(), (key, subject) -> {
			OriginMethod originMethod = originsByPath.get(path);
			if (originMethod == null) {
				throw new PathNotFoundException("Origin with path '" + path + "' not found");
			}

			JavaType responseType = originMethod.getResponseType();
			if (responseType.getRawClass() == None.class) {
				subject.next(None.SELF);
			}
			else {
				Object result;

				ContentType contentType = headers.getContentType();
				switch (contentType) {
					case BINARY:
						result = content;
						break;
					case JSON:
						try {
							result = JSON_MAPPER.readValue(content, responseType);
						}
						catch (IOException e) {
							throw new IncompatibleTypeException(
									String.format("Expected type %s cannot deserialized from given bytes",
											responseType.getGenericSignature()), e);
						}
						break;
					default:
						throw new UnsupportedContentTypeException(contentType);
				}

				try {
					subject.next(result);
				}
				catch (ClassCastException e) {
					throw IncompatibleTypeException.forMethodReturnType(originMethod.getRawMethod(), e);
				}
			}
			subject.complete();
			return null;
		});
	}

	private Object[] appendExtraArgs(Object[] simpleArgs, List<ExtraParam> extraParams,
									 Map<Class<? extends Annotation>, Object> extraArgs) {
		Object[] allArgs = new Object[simpleArgs.length + extraParams.size()];

		System.arraycopy(simpleArgs, 0, allArgs, 0, simpleArgs.length); // fill simple args

		for (ExtraParam extraParam : extraParams) {
			allArgs[extraParam.getIndex()] = extraArgs.get(extraParam.getAnnotation().annotationType());
		}

		return allArgs;
	}
}
