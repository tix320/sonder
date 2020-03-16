package com.github.tix320.sonder.internal.server.rpc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.github.tix320.kiwi.api.check.Try;
import com.github.tix320.kiwi.api.proxy.AnnotationBasedProxyCreator;
import com.github.tix320.kiwi.api.proxy.AnnotationInterceptor;
import com.github.tix320.kiwi.api.proxy.ProxyCreator;
import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.kiwi.api.reactive.publisher.Publisher;
import com.github.tix320.kiwi.api.util.IDGenerator;
import com.github.tix320.kiwi.api.util.None;
import com.github.tix320.sonder.api.common.communication.*;
import com.github.tix320.sonder.api.common.communication.Headers.HeadersBuilder;
import com.github.tix320.sonder.api.common.rpc.extra.ClientID;
import com.github.tix320.sonder.internal.common.communication.BuiltInProtocol;
import com.github.tix320.sonder.internal.common.communication.UnsupportedContentTypeException;
import com.github.tix320.sonder.internal.common.rpc.IncompatibleTypeException;
import com.github.tix320.sonder.internal.common.rpc.PathNotFoundException;
import com.github.tix320.sonder.internal.common.rpc.RPCProtocolException;
import com.github.tix320.sonder.internal.common.rpc.RPCRemoteException;
import com.github.tix320.sonder.internal.common.rpc.extra.ExtraArg;
import com.github.tix320.sonder.internal.common.rpc.extra.ExtraParam;
import com.github.tix320.sonder.internal.common.rpc.service.EndpointMethod;
import com.github.tix320.sonder.internal.common.rpc.service.OriginMethod;
import com.github.tix320.sonder.internal.common.rpc.service.Param;
import com.github.tix320.sonder.internal.common.rpc.service.ServiceMethod;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toUnmodifiableMap;

public final class ServerRPCProtocol implements Protocol {

	private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

	private final Map<Class<?>, ?> originServices;

	private final Map<Class<?>, ?> endpointServices;

	private final List<AnnotationInterceptor<?, ?>> endpointInterceptors;

	private final Map<Method, ServerOriginMethod> originsByMethod;

	private final Map<String, ServerOriginMethod> originsByPath;

	private final Map<String, EndpointMethod> endpointsByPath;

	private final Map<Long, Publisher<Object>> responsePublishers;

	private final IDGenerator transferIdGenerator;

	private final Publisher<Transfer> outgoingRequests;

	public ServerRPCProtocol(List<Class<?>> classes, List<AnnotationInterceptor<?, ?>> endpointInterceptors) {
		ServerOriginRPCServiceMethods originServiceMethods = new ServerOriginRPCServiceMethods(classes);
		ServerEndpointRPCServiceMethods endpointServiceMethods = new ServerEndpointRPCServiceMethods(classes);

		this.endpointInterceptors = endpointInterceptors;

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
				.collect(toUnmodifiableMap(clazz -> clazz, this::createOriginInstance));

		this.endpointServices = endpointServiceMethods.get()
				.stream()
				.map(ServiceMethod::getRawClass)
				.distinct()
				.collect(toUnmodifiableMap(clazz -> clazz, this::creatEndpointInstance));

		this.responsePublishers = new ConcurrentHashMap<>();
		this.transferIdGenerator = new IDGenerator();
		this.outgoingRequests = Publisher.simple();
	}

	@Override
	public void handleIncomingTransfer(Transfer transfer) {
		Headers headers = transfer.getHeaders();

		Boolean isInvoke = headers.getBoolean(Headers.IS_INVOKE);
		if (isInvoke != null && isInvoke) {
			try {
				processInvocation(transfer);
			}
			catch (Exception e) {
				e.printStackTrace();
				Boolean needResponse = headers.getBoolean(Headers.NEED_RESPONSE);
				if (needResponse != null && needResponse) {
					sendErrorResponse(headers, e);
				}
			}
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
		responsePublishers.values().forEach(Publisher::complete);
	}

	@SuppressWarnings("unchecked")
	public <T> T getService(Class<T> clazz) {
		T service = (T) originServices.get(clazz);
		if (service == null) {
			throw new IllegalArgumentException("Service of " + clazz + " not found");
		}
		return service;
	}

	private Object createOriginInstance(Class<?> clazz) {
		return Proxy.newProxyInstance(clazz.getClassLoader(), new Class[]{clazz}, new OriginInvocationHandler());
	}

	@SuppressWarnings("all")
	private Object creatEndpointInstance(Class<?> clazz) {
		ProxyCreator proxyCreator = new AnnotationBasedProxyCreator(clazz, endpointInterceptors);
		return proxyCreator.create();
	}

	private Object handleOriginCall(ServerOriginMethod method, List<Object> simpleArgs,
									Map<Class<? extends Annotation>, ExtraArg> extraArgs) {
		long clientId = (long) extraArgs.get(ClientID.class).getValue();

		Headers.HeadersBuilder builder = Headers.builder()
				.header(Headers.PATH, method.getPath())
				.header(Headers.DESTINATION_CLIENT_ID, clientId)
				.header(Headers.IS_INVOKE, true);

		Transfer transfer;
		switch (method.getRequestDataType()) {
			case ARGUMENTS:
				builder.contentType(ContentType.JSON);
				byte[] content = Try.supply(() -> JSON_MAPPER.writeValueAsBytes(simpleArgs))
						.getOrElseThrow(e -> new RPCProtocolException("Cannot convert arguments to JSON", e));
				transfer = new StaticTransfer(builder.build(), content);
				break;
			case BINARY:
				builder.contentType(ContentType.BINARY);
				transfer = new StaticTransfer(builder.build(), (byte[]) simpleArgs.get(0));
				break;
			case TRANSFER:
				builder.contentType(ContentType.TRANSFER);
				transfer = (Transfer) simpleArgs.get(0);
				transfer = new ChannelTransfer(transfer.getHeaders().compose().headers(builder.build()).build(),
						transfer.channel(), transfer.getContentLength());
				break;
			default:
				throw new RPCProtocolException(String.format("Unknown enum type %s", method.getRequestDataType()));
		}

		switch (method.getReturnType()) {
			case VOID:
				Headers headers = transfer.getHeaders().compose().header(Headers.NEED_RESPONSE, false).build();

				transfer = new ChannelTransfer(headers, transfer.channel(), transfer.getContentLength());

				outgoingRequests.publish(transfer);
				return null;
			case OBSERVABLE:
				long transferKey = transferIdGenerator.next();
				headers = transfer.getHeaders()
						.compose()
						.header(Headers.TRANSFER_KEY, transferKey)
						.header(Headers.NEED_RESPONSE, true)
						.build();

				transfer = new ChannelTransfer(headers, transfer.channel(), transfer.getContentLength());

				Publisher<Object> responsePublisher = Publisher.buffered(1);
				responsePublishers.put(transferKey, responsePublisher);
				outgoingRequests.publish(transfer);
				return responsePublisher.asObservable().toMono();
			default:
				throw new RPCProtocolException(String.format("Unknown enum type %s", method.getReturnType()));
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
					try {
						simpleArgs[i] = JSON_MAPPER.convertValue(argNode, param.getType());
					}
					catch (IllegalArgumentException e) {
						throw new RPCProtocolException(
								String.format("Fail to build object of type `%s` from json %s", param.getType(),
										argsNode.toPrettyString()), e);
					}
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

		Object[] args = appendExtraArgs(simpleArgs, endpointMethod.getExtraParams(),
				Map.of(ClientID.class, sourceClientId.longValue()));

		Object serviceInstance = endpointServices.get(endpointMethod.getRawClass());
		Object result = endpointMethod.invoke(serviceInstance, args);

		Boolean needResponse = headers.getBoolean(Headers.NEED_RESPONSE);
		if (needResponse != null && needResponse) {
			HeadersBuilder builder = Headers.builder();
			builder.header(Headers.PATH, path)
					.header(Headers.TRANSFER_KEY, headers.get(Headers.TRANSFER_KEY))
					.header(Headers.DESTINATION_CLIENT_ID, sourceClientId);

			switch (endpointMethod.resultType()) {
				case VOID:
					builder.contentType(ContentType.BINARY);
					outgoingRequests.publish(new StaticTransfer(builder.build(), new byte[0]));
					break;
				case OBJECT:
					builder.contentType(ContentType.JSON);
					byte[] transferContent = Try.supply(() -> JSON_MAPPER.writeValueAsBytes(result))
							.getOrElseThrow((e) -> new RPCProtocolException(
									String.format("Cannot serialize object: %s", result), e));
					outgoingRequests.publish(new StaticTransfer(builder.build(), transferContent));
					break;
				case BINARY:
					builder.contentType(ContentType.BINARY);
					outgoingRequests.publish(new StaticTransfer(builder.build(), (byte[]) result));
					break;
				case TRANSFER:
					builder.contentType(ContentType.TRANSFER);
					Transfer resultTransfer = (Transfer) result;
					outgoingRequests.publish(
							new ChannelTransfer(resultTransfer.getHeaders().compose().headers(builder.build()).build(),
									resultTransfer.channel(), resultTransfer.getContentLength()));
					break;
				default:
					throw new IllegalStateException();
			}
		}
	}

	private void processResult(Transfer transfer) {
		Boolean isProtocolErrorResponse = transfer.getHeaders().getBoolean(Headers.IS_RPC_PROTOCOL_ERROR_RESPONSE);
		if (isProtocolErrorResponse != null && isProtocolErrorResponse) {
			processErrorResult(transfer);
		}
		else {
			processSuccessResult(transfer);
		}
	}

	private void processErrorResult(Transfer transfer) {
		Number transferKey = transfer.getHeaders().getNonNullNumber(Headers.TRANSFER_KEY);
		responsePublishers.computeIfPresent(transferKey.longValue(), (key, publisher) -> {
			byte[] content = Try.supplyOrRethrow(transfer::readAll);

			RPCRemoteException rpcRemoteException = new RPCRemoteException(new String(content));
			rpcRemoteException.printStackTrace();
			publisher.publishError(rpcRemoteException);
			return null;
		});
	}

	private void processSuccessResult(Transfer transfer) {
		Headers headers = transfer.getHeaders();

		String path = headers.getNonNullString(Headers.PATH);

		Number transferKey = headers.getNonNullNumber(Headers.TRANSFER_KEY);

		responsePublishers.computeIfPresent(transferKey.longValue(), (key, publisher) -> {
			OriginMethod originMethod = originsByPath.get(path);
			if (originMethod == null) {
				throw new PathNotFoundException("Origin with path '" + path + "' not found");
			}

			JavaType returnJavaType = originMethod.getReturnJavaType();
			if (returnJavaType.getRawClass() == None.class) {
				Try.runOrRethrow(transfer::readAllInVain);
				publisher.publish(None.SELF);
			}
			else if (transfer.getContentLength() == 0) {
				throw new IllegalStateException(
						String.format("Response content is empty, and it cannot be converted to type %s",
								returnJavaType));
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
							result = JSON_MAPPER.readValue(transfer.readAll(), returnJavaType);
						}
						catch (IOException e) {
							throw new IncompatibleTypeException(
									String.format("Expected type %s cannot deserialized from given bytes",
											returnJavaType.getGenericSignature()), e);
						}
						break;
					case TRANSFER:
						result = transfer;
						break;
					default:
						throw new UnsupportedContentTypeException(contentType);
				}
				try {
					publisher.publish(result);
				}
				catch (ClassCastException e) {
					throw IncompatibleTypeException.forMethodReturnType(originMethod.getRawMethod(), e);
				}
			}
			publisher.complete();
			return null;
		});
	}

	private void sendErrorResponse(Headers headers, Exception e) {
		Number clientId = headers.getNonNullNumber(Headers.SOURCE_CLIENT_ID);
		Number transferKey = headers.getNonNullNumber(Headers.TRANSFER_KEY);

		headers = Headers.builder()
				.header(Headers.IS_RPC_PROTOCOL_ERROR_RESPONSE, true)
				.header(Headers.DESTINATION_CLIENT_ID, clientId)
				.header(Headers.TRANSFER_KEY, transferKey)
				.build();

		ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
		e.printStackTrace(new PrintStream(byteStream));
		byte[] content = byteStream.toByteArray();

		Transfer transfer = new StaticTransfer(headers, content);
		outgoingRequests.publish(transfer);
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

	private final class OriginInvocationHandler implements InvocationHandler {

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) {
			if (method.getDeclaringClass() == Object.class) {
				throw new UnsupportedOperationException("This method does not allowed on origin services");
			}

			ServerOriginMethod originMethod = originsByMethod.get(method);
			List<Param> simpleParams = originMethod.getSimpleParams();
			List<ExtraParam> extraParams = originMethod.getExtraParams();

			List<Object> simpleArgs = new ArrayList<>(simpleParams.size());
			Map<Class<? extends Annotation>, ExtraArg> extraArgs = new HashMap<>(extraParams.size());

			for (Param simpleParam : simpleParams) {
				int index = simpleParam.getIndex();
				simpleArgs.add(args[index]);
			}

			for (ExtraParam extraParam : extraParams) {
				int index = extraParam.getIndex();
				extraArgs.put(extraParam.getAnnotation().annotationType(),
						new ExtraArg(args[index], extraParam.getAnnotation()));
			}

			return handleOriginCall(originMethod, simpleArgs, extraArgs);
		}
	}
}
