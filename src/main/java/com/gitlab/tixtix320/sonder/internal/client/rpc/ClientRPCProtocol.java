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

	private final Map<Long, Subject<Object>> responseSubjects;

	private final IDGenerator transferIdGenerator;

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

		Transfer transfer;
		switch (method.requestDataType()) {
			case ARGUMENTS:
				builder.contentType(ContentType.JSON);
				byte[] content = Try.supplyOrRethrow(() -> JSON_MAPPER.writeValueAsBytes(simpleArgs));
				transfer = new StaticTransfer(builder.build(), content);
				break;
			case BINARY:
				builder.contentType(ContentType.BINARY);
				transfer = new StaticTransfer(builder.build(), (byte[]) simpleArgs.get(0));
				break;
			case TRANSFER:
				builder.contentType(ContentType.TRANSFER);
				transfer = (Transfer) simpleArgs.get(0);
				break;
			default:
				throw new IllegalStateException();
		}

		if (method.needResponse()) {
			long transferKey = transferIdGenerator.next();
			Headers headers = transfer.getHeaders()
					.compose()
					.header(Headers.TRANSFER_KEY, transferKey)
					.header(Headers.NEED_RESPONSE, true)
					.build();

			transfer = new ChannelTransfer(headers, transfer.channel(), transfer.getContentLength());

			Subject<Object> responseSubject = Subject.single();
			responseSubjects.put(transferKey, responseSubject);
			outgoingRequests.next(transfer);
			return responseSubject.asObservable();
		}
		else {
			Headers headers = transfer.getHeaders().compose().header(Headers.NEED_RESPONSE, false).build();

			transfer = new ChannelTransfer(headers, transfer.channel(), transfer.getContentLength());

			outgoingRequests.next(transfer);
			return null;
		}
	}

	private void processInvocation(Transfer transfer)
			throws IOException {
		Headers headers = transfer.getHeaders();

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
							"The content type is %s. Consequently endpoint method %s(%s) must have only one parameter with type byte[]",
							ContentType.BINARY.name(), endpointMethod.getRawMethod().getName(),
							endpointMethod.getRawClass().getName()));
				}
				simpleArgs[0] = transfer.readAll();
				break;
			case JSON:
				ArrayNode argsNode;
				try {
					argsNode = JSON_MAPPER.readValue(transfer.readAll(), ArrayNode.class);
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
			case TRANSFER:
				if (simpleArgs.length != 1) {
					throw new IllegalStateException(String.format(
							"The content type is %s. Consequently endpoint method %s(%s) must have only one parameter with type %s",
							ContentType.TRANSFER.name(), endpointMethod.getRawMethod().getName(),
							endpointMethod.getRawClass().getName(), Transfer.class.getName()));
				}
				simpleArgs[0] = transfer;
				break;
			default:
				throw new UnsupportedContentTypeException(contentType);
		}

		Map<Class<? extends Annotation>, Object> extraArgs = new HashMap<>();
		extraArgs.put(ClientID.class, sourceClientId);

		Object serviceInstance = endpointServices.get(endpointMethod.getRawClass());
		Object[] args = appendExtraArgs(simpleArgs, endpointMethod.getExtraParams(), extraArgs);
		Object result = endpointMethod.invoke(serviceInstance, args);

		Boolean needResponse = headers.getBoolean(Headers.NEED_RESPONSE);
		if (needResponse != null && needResponse) {
			HeadersBuilder builder = Headers.builder();
			builder.header(Headers.PATH, path)
					.header(Headers.TRANSFER_KEY, headers.get(Headers.TRANSFER_KEY))
					.header(Headers.DESTINATION_CLIENT_ID, sourceClientId);

			switch (endpointMethod.resultType()) {
				case OBJECT:
					builder.contentType(ContentType.JSON);
					byte[] transferContent = Try.supplyOrRethrow(() -> JSON_MAPPER.writeValueAsBytes(result));
					outgoingRequests.next(new StaticTransfer(builder.build(), transferContent));
					break;
				case BINARY:
					builder.contentType(ContentType.BINARY);
					outgoingRequests.next(new StaticTransfer(builder.build(), (byte[]) result));
					break;
				case TRANSFER:
					builder.contentType(ContentType.TRANSFER);
					Transfer resultTransfer = (Transfer) result;
					outgoingRequests.next(new ChannelTransfer(builder.build(), resultTransfer.channel(),
							resultTransfer.getContentLength()));
					break;
				default:
					throw new IllegalStateException();
			}
		}
	}

	private void processResult(Transfer transfer)
			throws IOException {
		final Headers headers = transfer.getHeaders();

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
						result = Try.supplyOrRethrow(transfer::readAll);
						break;
					case JSON:
						try {
							result = JSON_MAPPER.readValue(transfer.readAll(), responseType);
						}
						catch (IOException e) {
							throw new IncompatibleTypeException(
									String.format("Expected type %s cannot deserialized from given bytes",
											responseType.getGenericSignature()), e);
						}
						break;
					case TRANSFER:
						result = transfer;
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
