package com.github.tix320.sonder.internal.common.rpc.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.github.tix320.kiwi.api.check.Try;
import com.github.tix320.kiwi.api.proxy.AnnotationBasedProxyCreator;
import com.github.tix320.kiwi.api.proxy.AnnotationInterceptor;
import com.github.tix320.kiwi.api.proxy.ProxyCreator;
import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.kiwi.api.reactive.observable.Subscriber;
import com.github.tix320.kiwi.api.reactive.observable.Subscription;
import com.github.tix320.kiwi.api.reactive.publisher.MonoPublisher;
import com.github.tix320.kiwi.api.reactive.publisher.Publisher;
import com.github.tix320.kiwi.api.reactive.publisher.SimplePublisher;
import com.github.tix320.kiwi.api.util.IDGenerator;
import com.github.tix320.kiwi.api.util.None;
import com.github.tix320.sonder.api.common.communication.*;
import com.github.tix320.sonder.api.common.communication.Headers.HeadersBuilder;
import com.github.tix320.sonder.api.common.rpc.extra.ClientID;
import com.github.tix320.sonder.internal.client.rpc.ClientEndpointRPCServiceMethods;
import com.github.tix320.sonder.internal.client.rpc.ClientOriginRPCServiceMethods;
import com.github.tix320.sonder.internal.common.BuiltInProtocol;
import com.github.tix320.sonder.internal.common.ProtocolOrientation;
import com.github.tix320.sonder.internal.common.communication.UnsupportedContentTypeException;
import com.github.tix320.sonder.internal.common.rpc.IncompatibleTypeException;
import com.github.tix320.sonder.internal.common.rpc.PathNotFoundException;
import com.github.tix320.sonder.internal.common.rpc.RPCProtocolException;
import com.github.tix320.sonder.internal.common.rpc.RPCRemoteException;
import com.github.tix320.sonder.internal.common.rpc.extra.ExtraArg;
import com.github.tix320.sonder.internal.common.rpc.extra.ExtraParam;
import com.github.tix320.sonder.internal.common.rpc.service.*;
import com.github.tix320.sonder.internal.common.rpc.service.OriginMethod.ReturnType;
import com.github.tix320.sonder.internal.common.util.Threads;
import com.github.tix320.sonder.internal.server.rpc.ServerEndpointRPCServiceMethods;
import com.github.tix320.sonder.internal.server.rpc.ServerOriginRPCServiceMethods;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toUnmodifiableMap;

public class RPCProtocol implements Protocol {

	private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

	private final ProtocolOrientation orientation;

	private final Map<Class<?>, ?> originServices;

	private final Map<Class<?>, ?> endpointServices;

	private final Map<Method, OriginMethod> originsByMethod;

	private final Map<String, EndpointMethod> endpointsByPath;

	private final Map<Long, RequestMetadata> requestMetadataByResponseKey;

	private final Map<Long, Subscription> realSubscriptions;

	private final IDGenerator responseKeyGenerator;

	private final Publisher<Transfer> outgoingRequests;

	public RPCProtocol(ProtocolOrientation orientation, List<Class<?>> classes,
					   List<AnnotationInterceptor<?, ?>> endpointInterceptors) {
		this.orientation = orientation;

		OriginRPCServiceMethods<OriginMethod> originServiceMethods =
				orientation == ProtocolOrientation.SERVER ? new ServerOriginRPCServiceMethods(classes) :
						new ClientOriginRPCServiceMethods(classes);

		EndpointRPCServiceMethods<EndpointMethod> endpointServiceMethods =
				orientation == ProtocolOrientation.SERVER ? new ServerEndpointRPCServiceMethods(classes) :
						new ClientEndpointRPCServiceMethods(classes);

		this.originsByMethod = originServiceMethods.get()
				.stream()
				.collect(toUnmodifiableMap(ServiceMethod::getRawMethod, identity()));

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
				.collect(
						toUnmodifiableMap(clazz -> clazz, clazz -> creatEndpointInstance(clazz, endpointInterceptors)));

		this.requestMetadataByResponseKey = new ConcurrentHashMap<>();
		this.realSubscriptions = new ConcurrentHashMap<>();
		this.responseKeyGenerator = new IDGenerator();
		this.outgoingRequests = Publisher.simple();
	}

	@Override
	public final void handleIncomingTransfer(Transfer transfer) {
		Headers headers = transfer.getHeaders();

		TransferType transferType = TransferType.valueOf(headers.getNonNullString(Headers.TRANSFER_TYPE));

		switch (transferType) {
			case INVOCATION:
				processMethodInvocation(transfer);
				break;
			case INVOCATION_RESULT:
				processInvocationResult(transfer);
				break;
			case SUBSCRIPTION_RESULT:
				processSubscriptionResult(transfer);
				break;
			case ERROR_RESULT:
				processErrorResult(transfer);
				break;
			default:
				throw new IllegalStateException();
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
		requestMetadataByResponseKey.values()
				.forEach(requestMetadata -> requestMetadata.getResponsePublisher().complete());
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
	private Object creatEndpointInstance(Class<?> clazz, List<AnnotationInterceptor<?, ?>> endpointInterceptors) {
		ProxyCreator proxyCreator = new AnnotationBasedProxyCreator(clazz, endpointInterceptors);
		return proxyCreator.create();
	}

	private Object handleOriginCall(OriginMethod method, List<Object> simpleArgs,
									Map<Class<? extends Annotation>, ExtraArg> extraArgs) {
		Long clientId;
		if (orientation == ProtocolOrientation.SERVER) {
			clientId = (long) extraArgs.get(ClientID.class).getValue();
		}
		else {
			clientId = (Long) Optional.ofNullable(extraArgs.get(ClientID.class)).map(ExtraArg::getValue).orElse(null);
		}

		Headers.HeadersBuilder builder = Headers.builder()
				.header(Headers.DESTINATION_ID, clientId)
				.header(Headers.TRANSFER_TYPE, TransferType.INVOCATION)
				.header(Headers.PATH, method.getPath());

		Transfer transferToSend;
		switch (method.getRequestDataType()) {
			case ARGUMENTS:
				builder.contentType(ContentType.JSON);
				byte[] content = Try.supply(() -> JSON_MAPPER.writeValueAsBytes(simpleArgs))
						.getOrElseThrow(e -> new RPCProtocolException("Cannot convert arguments to JSON", e));
				transferToSend = new StaticTransfer(builder.build(), content);
				break;
			case BINARY:
				builder.contentType(ContentType.BINARY);
				transferToSend = new StaticTransfer(builder.build(), (byte[]) simpleArgs.get(0));
				break;
			case TRANSFER:
				builder.contentType(ContentType.TRANSFER);
				transferToSend = (Transfer) simpleArgs.get(0);
				transferToSend = new ChannelTransfer(
						transferToSend.getHeaders().compose().headers(builder.build()).build(),
						transferToSend.channel(), transferToSend.getContentLength());
				break;
			default:
				throw new IllegalStateException();
		}

		switch (method.getReturnType()) {
			case VOID:
				Transfer transfer = transferToSend;
				Threads.runAsync(() -> outgoingRequests.publish(transfer));
				return null;
			case ASYNC_RESPONSE:
				long responseKey = responseKeyGenerator.next();
				Headers headers = transferToSend.getHeaders()
						.compose()
						.header(Headers.RESPONSE_KEY, responseKey)
						.header(Headers.NEED_RESPONSE, true)
						.build();

				MonoPublisher<Object> responsePublisher = Publisher.mono();
				requestMetadataByResponseKey.put(responseKey, new RequestMetadata(responsePublisher, method));

				transfer = new ChannelTransfer(headers, transferToSend.channel(), transferToSend.getContentLength());
				Threads.runAsync(() -> outgoingRequests.publish(transfer));
				return responsePublisher.asObservable();
			case SUBSCRIPTION:
				responseKey = responseKeyGenerator.next();
				headers = transferToSend.getHeaders()
						.compose()
						.header(Headers.RESPONSE_KEY, responseKey)
						.header(Headers.NEED_RESPONSE, true)
						.build();

				transfer = new ChannelTransfer(headers, transferToSend.channel(), transferToSend.getContentLength());

				SimplePublisher<Object> dummyPublisher = Publisher.simple();
				requestMetadataByResponseKey.put(responseKey, new RequestMetadata(dummyPublisher, method));

				Observable<Object> observable = dummyPublisher.asObservable();
				DummyObservable dummyObservable = new DummyObservable(observable, clientId, responseKey);

				Threads.runAsync(() -> outgoingRequests.publish(transfer));
				return dummyObservable;
			default:
				throw new RPCProtocolException(String.format("Unknown enum type %s", method.getReturnType()));
		}
	}


	private void processMethodInvocation(Transfer transfer) {
		Headers headers = transfer.getHeaders();

		String path = headers.getNonNullString(Headers.PATH);

		EndpointMethod endpointMethod = endpointsByPath.get(path);
		if (endpointMethod == null) {
			onEndpointPathNotFound(headers);
			throw new PathNotFoundException("Endpoint with path '" + path + "' not found");
		}

		ContentType contentType = headers.getContentType();

		Object[] simpleArgs;

		switch (contentType) {
			case BINARY:
				simpleArgs = extractArgsFromBinaryRequest(transfer, endpointMethod);
				break;
			case JSON:
				simpleArgs = extractArgsFromJsonRequest(transfer, endpointMethod);
				break;
			case TRANSFER:
				simpleArgs = extractArgsFromTransferRequest(transfer, endpointMethod);
				break;
			default:
				throw new UnsupportedContentTypeException(contentType);
		}

		Map<Class<? extends Annotation>, Object> extraArgs = extractExtraArgs(headers);
		Object[] args = concatSimpleAndExtraArgs(simpleArgs, endpointMethod.getExtraParams(), extraArgs);

		Object serviceInstance = endpointServices.get(endpointMethod.getRawClass());

		Threads.runAsync(() -> {
			Object result = endpointMethod.invoke(serviceInstance, args);

			boolean needResponse = headers.has(Headers.NEED_RESPONSE);

			if (needResponse) {
				processEndpointMethodResult(headers, endpointMethod, result);
			}
		});
	}

	private void processErrorResult(Transfer transfer) {
		Headers headers = transfer.getHeaders();
		long responseKey = headers.getNonNullLong(Headers.RESPONSE_KEY);
		RequestMetadata requestMetadata = requestMetadataByResponseKey.remove(responseKey);
		Publisher<Object> responsePublisher = requestMetadata.getResponsePublisher();
		if (responsePublisher == null) {
			throw new RPCProtocolException(
					String.format("Response publisher not found for response key %s", responseKey));
		}

		byte[] content = Try.supplyOrRethrow(transfer::readAll);

		ErrorType errorType = ErrorType.valueOf(headers.getNonNullString(Headers.ERROR_TYPE));
		Exception exception;
		switch (errorType) {
			case PATH_NOT_FOUND:
				String path = headers.getNonNullString(Headers.PATH);
				exception = new RPCRemoteException(
						new PathNotFoundException("Endpoint with path '" + path + "' not found"));
				break;
			case INCOMPATIBLE_REQUEST:
				exception = new RPCRemoteException(new String(content));
				break;
			default:
				exception = new RPCRemoteException(new RuntimeException("Unknown error"));
		}

		Threads.runAsync(() -> {
			responsePublisher.publishError(exception);
			responsePublisher.complete();
		});
	}

	private void processSubscriptionResult(Transfer transfer) {
		Headers headers = transfer.getHeaders();

		SubscriptionActionType subscriptionActionType = SubscriptionActionType.valueOf(
				headers.getNonNullString(Headers.SUBSCRIPTION_ACTION_TYPE));

		long responseKey = headers.getNonNullLong(Headers.RESPONSE_KEY);

		switch (subscriptionActionType) {
			case UNSUBSCRIBE:
				Subscription subscription = realSubscriptions.remove(responseKey);
				if (subscription != null) {
					subscription.unsubscribe();
				}
				break;
			case SUBSCRIPTION_COMPLETED:
				RequestMetadata metadata = requestMetadataByResponseKey.remove(responseKey);
				if (metadata != null) {
					Publisher<Object> publisher = metadata.getResponsePublisher();
					publisher.complete();
				}
				break;
			case REGULAR_ITEM:
				RequestMetadata requestMetadata = requestMetadataByResponseKey.get(responseKey);
				if (requestMetadata == null) {
					throw new IllegalStateException(
							String.format("Request metadata not found for response key %s", responseKey));
				}

				OriginMethod originMethod = requestMetadata.getOriginMethod();
				if (originMethod.getReturnType() != ReturnType.SUBSCRIPTION) {
					throw new RPCProtocolException(
							String.format("Subscription result was received, but origin method %s(%s) not expect it",
									originMethod.getRawClass().getName(), originMethod.getRawMethod().getName()));
				}

				Publisher<Object> dummyPublisher = requestMetadata.getResponsePublisher();

				JavaType returnJavaType = originMethod.getReturnJavaType();
				Object value = deserializeObject(Try.supplyOrRethrow(transfer::readAll), returnJavaType);
				Threads.runAsync(() -> dummyPublisher.publish(value));
				break;
			default:
				throw new IllegalStateException();
		}
	}

	private void processInvocationResult(Transfer transfer) {
		Headers headers = transfer.getHeaders();

		long responseKey = headers.getNonNullNumber(Headers.RESPONSE_KEY).longValue();

		RequestMetadata requestMetadata = requestMetadataByResponseKey.remove(responseKey);
		if (requestMetadata == null) {
			throw new RPCProtocolException(String.format("Invalid transfer key `%s`", responseKey));
		}

		OriginMethod originMethod = requestMetadata.getOriginMethod();
		Publisher<Object> responsePublisher = requestMetadata.getResponsePublisher();

		JavaType returnJavaType = originMethod.getReturnJavaType();
		if (returnJavaType.getRawClass() == None.class) {
			Try.runOrRethrow(transfer::readAllInVain);

			Threads.runAsync(() -> responsePublisher.publish(None.SELF));
		}
		else if (transfer.getContentLength() == 0) {
			throw new IllegalStateException(
					String.format("Response content is empty, and it cannot be converted to type %s", returnJavaType));
		}
		else {
			ContentType contentType = headers.getContentType();

			Object result;
			switch (contentType) {
				case BINARY:
					result = Try.supplyOrRethrow(transfer::readAll);
					break;
				case JSON:
					result = deserializeObject(Try.supplyOrRethrow(transfer::readAll), returnJavaType);
					break;
				case TRANSFER:
					result = transfer;
					break;
				default:
					throw new UnsupportedContentTypeException(contentType);
			}

			Threads.runAsync(() -> {
				try {
					responsePublisher.publish(result);
				}
				catch (ClassCastException e) {
					throw IncompatibleTypeException.forMethodReturnType(originMethod.getRawMethod(), e);
				}
			});
		}
	}

	private Object[] extractArgsFromBinaryRequest(Transfer transfer, EndpointMethod endpointMethod) {
		Headers headers = transfer.getHeaders();

		List<Param> simpleParams = endpointMethod.getSimpleParams();

		if (simpleParams.size() != 1) {
			String errorMessage = String.format(
					"The content type is %s. Consequently endpoint method %s(%s) must have only one parameter with type byte[]",
					ContentType.BINARY.name(), endpointMethod.getRawMethod().getName(),
					endpointMethod.getRawClass().getName());
			onIncompatibilityRequestAndMethod(headers,
					Try.supplyOrRethrow(() -> JSON_MAPPER.writeValueAsBytes(errorMessage)));
			throw new RPCProtocolException(errorMessage);
		}

		byte[] data = Try.supplyOrRethrow(transfer::readAll);

		return new Object[]{data};
	}

	private Object[] extractArgsFromJsonRequest(Transfer transfer, EndpointMethod endpointMethod) {
		Headers headers = transfer.getHeaders();

		List<Param> simpleParams = endpointMethod.getSimpleParams();

		ArrayNode argsNode;
		byte[] content = Try.supplyOrRethrow(transfer::readAll);
		try {
			argsNode = JSON_MAPPER.readValue(content, ArrayNode.class);
		}
		catch (IOException e) {
			onIncompatibilityRequestAndMethod(headers, exceptionStacktraceToBytes(e));
			throw new RPCProtocolException("Cannot parse bytes to ArrayNode", e);
		}

		Object[] simpleArgs = new Object[simpleParams.size()];
		for (int i = 0; i < simpleParams.size(); i++) {
			JsonNode argNode = argsNode.get(i);
			Param param = simpleParams.get(i);
			try {
				simpleArgs[i] = JSON_MAPPER.convertValue(argNode, param.getType());
			}
			catch (IllegalArgumentException e) {
				onIncompatibilityRequestAndMethod(headers, exceptionStacktraceToBytes(e));
				throw new RPCProtocolException(
						String.format("Fail to build object of type `%s` from json %s", param.getType(),
								argsNode.toPrettyString()), e);
			}
		}

		return simpleArgs;
	}

	private Object[] extractArgsFromTransferRequest(Transfer transfer, EndpointMethod endpointMethod) {
		Headers headers = transfer.getHeaders();
		List<Param> simpleParams = endpointMethod.getSimpleParams();

		if (simpleParams.size() != 1) {
			String errorMessage = String.format(
					"The content type is %s. Consequently endpoint method %s(%s) must have only one parameter with type %s",
					ContentType.TRANSFER.name(), endpointMethod.getRawMethod().getName(),
					endpointMethod.getRawClass().getName(), Transfer.class.getName());
			onIncompatibilityRequestAndMethod(headers,
					Try.supplyOrRethrow(() -> JSON_MAPPER.writeValueAsBytes(errorMessage)));

			throw new RPCProtocolException(errorMessage);
		}

		return new Object[]{transfer};
	}

	private Map<Class<? extends Annotation>, Object> extractExtraArgs(Headers headers) {
		Long sourceClientId = headers.getLong(Headers.SOURCE_ID);

		if (orientation == ProtocolOrientation.SERVER) {
			return Map.of(ClientID.class, sourceClientId); // sourceClientId is not null in SERVER orientation
		}
		else {
			Map<Class<? extends Annotation>, Object> extraArgs = new HashMap<>();
			extraArgs.put(ClientID.class, sourceClientId);
			return extraArgs;
		}
	}

	private void processEndpointMethodResult(Headers headers, EndpointMethod endpointMethod, Object result) {
		HeadersBuilder builder = Headers.builder();

		Long sourceId = headers.getLong(Headers.SOURCE_ID);
		long responseKey = headers.getNonNullLong(Headers.RESPONSE_KEY);

		builder.header(Headers.TRANSFER_TYPE, TransferType.INVOCATION_RESULT)
				.header(Headers.RESPONSE_KEY, responseKey)
				.header(Headers.DESTINATION_ID, sourceId);

		switch (endpointMethod.resultType()) {
			case VOID:
				builder.contentType(ContentType.BINARY);
				Threads.runAsync(() -> outgoingRequests.publish(new StaticTransfer(builder.build(), new byte[0])));
				break;
			case OBJECT:
				builder.contentType(ContentType.JSON);
				byte[] transferContent = serializeObject(result);
				Threads.runAsync(() -> outgoingRequests.publish(new StaticTransfer(builder.build(), transferContent)));
				break;
			case BINARY:
				builder.contentType(ContentType.BINARY);
				Threads.runAsync(() -> outgoingRequests.publish(new StaticTransfer(builder.build(), (byte[]) result)));
				break;
			case TRANSFER:
				builder.contentType(ContentType.TRANSFER);
				Transfer resultTransfer = (Transfer) result;
				Headers finalHeaders = resultTransfer.getHeaders().compose().headers(builder.build()).build();
				Threads.runAsync(() -> outgoingRequests.publish(
						new ChannelTransfer(finalHeaders, resultTransfer.channel(),
								resultTransfer.getContentLength())));
				break;
			case SUBSCRIPTION:
				Observable<?> observable = (Observable<?>) result;

				observable.subscribe(new LocalSubscriber(sourceId, responseKey));
				break;
			default:
				throw new IllegalStateException();
		}
	}

	private void onEndpointPathNotFound(Headers headers) {
		Headers errorHeaders = Headers.builder()
				.header(Headers.DESTINATION_ID, headers.getLong(Headers.SOURCE_ID))
				.header(Headers.RESPONSE_KEY, headers.getLong(Headers.RESPONSE_KEY))
				.header(Headers.ERROR_TYPE, ErrorType.PATH_NOT_FOUND)
				.header(Headers.PATH, headers.getNonNullString(Headers.PATH))
				.build();

		sendErrorResult(errorHeaders, new byte[0]);
	}

	private void onIncompatibilityRequestAndMethod(Headers headers, byte[] content) {
		Headers errorHeaders = Headers.builder()
				.header(Headers.DESTINATION_ID, headers.getLong(Headers.SOURCE_ID))
				.header(Headers.RESPONSE_KEY, headers.getLong(Headers.RESPONSE_KEY))
				.header(Headers.ERROR_TYPE, ErrorType.INCOMPATIBLE_REQUEST)
				.build();

		sendErrorResult(errorHeaders, content);
	}

	private void sendErrorResult(Headers headers, byte[] content) {
		headers = headers.compose().header(Headers.TRANSFER_TYPE, TransferType.ERROR_RESULT.name()).build();

		Transfer transfer = new StaticTransfer(headers, content);
		Threads.runAsync(() -> outgoingRequests.publish(transfer));
	}

	private static byte[] exceptionStacktraceToBytes(Throwable e) {
		ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
		e.printStackTrace(new PrintStream(byteStream));
		return byteStream.toByteArray();
	}

	private static Object[] concatSimpleAndExtraArgs(Object[] simpleArgs, List<ExtraParam> extraParams,
													 Map<Class<? extends Annotation>, Object> extraArgs) {
		Object[] allArgs = new Object[simpleArgs.length + extraParams.size()];

		System.arraycopy(simpleArgs, 0, allArgs, 0, simpleArgs.length); // fill simple args

		for (ExtraParam extraParam : extraParams) {
			allArgs[extraParam.getIndex()] = extraArgs.get(extraParam.getAnnotation().annotationType());
		}

		return allArgs;
	}

	private static byte[] serializeObject(Object object) {
		try {
			return JSON_MAPPER.writeValueAsBytes(object);
		}
		catch (JsonProcessingException e) {
			throw new RPCProtocolException(String.format("Cannot serialize object: %s", object), e);
		}
	}

	private static Object deserializeObject(byte[] bytes, JavaType expectedType) {
		try {
			return JSON_MAPPER.readValue(bytes, expectedType);
		}
		catch (IOException e) {
			throw new IncompatibleTypeException(String.format("Expected type %s cannot deserialized from given bytes",
					expectedType.getGenericSignature()), e);
		}
	}

	private final class OriginInvocationHandler implements InvocationHandler {

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) {
			if (method.getDeclaringClass() == Object.class) {
				throw new UnsupportedOperationException("This method does not allowed on origin services");
			}

			OriginMethod originMethod = originsByMethod.get(method);
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

	private final class DummyObservable implements Observable<Object> {

		private final Observable<Object> observable;

		private final Long destinationId;

		private final long responseKey;

		private DummyObservable(Observable<Object> observable, Long destinationId, long responseKey) {
			this.observable = observable;
			this.destinationId = destinationId;
			this.responseKey = responseKey;
		}

		@Override
		public Subscription subscribe(Subscriber<? super Object> subscriber) {
			return observable.subscribe(new Subscriber<>() {
				@Override
				public void onSubscribe(Subscription subscription) {
					subscriber.onSubscribe(subscription);
				}

				@Override
				public boolean onPublish(Object item) {
					return subscriber.onPublish(item);
				}

				@Override
				public boolean onError(Throwable throwable) {
					return subscriber.onError(throwable);
				}

				@Override
				public void onComplete() {
					Headers headers = Headers.builder()
							.header(Headers.DESTINATION_ID, destinationId)
							.header(Headers.TRANSFER_TYPE, TransferType.SUBSCRIPTION_RESULT)
							.header(Headers.SUBSCRIPTION_ACTION_TYPE, SubscriptionActionType.UNSUBSCRIBE)
							.header(Headers.RESPONSE_KEY, responseKey)
							.build();

					Transfer transfer = new StaticTransfer(headers, new byte[0]);
					requestMetadataByResponseKey.remove(responseKey);
					subscriber.onComplete();

					Threads.runAsync(() -> outgoingRequests.publish(transfer));
				}
			});
		}
	}

	public class LocalSubscriber implements Subscriber<Object> {

		private final Long destinationId;
		private final long responseKey;

		public LocalSubscriber(Long destinationId, long responseKey) {
			this.destinationId = destinationId;
			this.responseKey = responseKey;
		}

		@Override
		public void onSubscribe(Subscription subscription) {
			realSubscriptions.put(responseKey, subscription);
		}

		@Override
		public boolean onPublish(Object item) {
			Headers headers = Headers.builder()
					.header(Headers.DESTINATION_ID, destinationId)
					.header(Headers.TRANSFER_TYPE, TransferType.SUBSCRIPTION_RESULT)
					.header(Headers.SUBSCRIPTION_ACTION_TYPE, SubscriptionActionType.REGULAR_ITEM)
					.header(Headers.RESPONSE_KEY, responseKey)
					.build();

			byte[] content = serializeObject(item);
			Transfer transferToSend = new StaticTransfer(headers, content);

			Threads.runAsync(() -> outgoingRequests.publish(transferToSend));

			return true;
		}

		@Override
		public boolean onError(Throwable throwable) {
			Headers headers = Headers.builder()
					.header(Headers.DESTINATION_ID, destinationId)
					.header(Headers.TRANSFER_TYPE, TransferType.ERROR_RESULT)
					.header(Headers.RESPONSE_KEY, responseKey)
					.build();

			byte[] content = exceptionStacktraceToBytes(throwable);
			Transfer transferToSend = new StaticTransfer(headers, content);

			Threads.runAsync(() -> outgoingRequests.publish(transferToSend));

			return true;
		}

		@Override
		public void onComplete() {
			Headers headers = Headers.builder()
					.header(Headers.DESTINATION_ID, destinationId)
					.header(Headers.TRANSFER_TYPE, TransferType.SUBSCRIPTION_RESULT)
					.header(Headers.SUBSCRIPTION_ACTION_TYPE, SubscriptionActionType.SUBSCRIPTION_COMPLETED)
					.header(Headers.RESPONSE_KEY, responseKey)
					.contentType(ContentType.BINARY)
					.build();

			Transfer transferToSend = new StaticTransfer(headers, new byte[0]);

			Threads.runAsync(() -> outgoingRequests.publish(transferToSend));
		}
	}

}
