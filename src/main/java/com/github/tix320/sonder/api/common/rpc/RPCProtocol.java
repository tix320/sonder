package com.github.tix320.sonder.api.common.rpc;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.github.tix320.kiwi.api.reactive.observable.CompletionType;
import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.kiwi.api.reactive.observable.Subscriber;
import com.github.tix320.kiwi.api.reactive.observable.Subscription;
import com.github.tix320.kiwi.api.reactive.publisher.MonoPublisher;
import com.github.tix320.kiwi.api.reactive.publisher.Publisher;
import com.github.tix320.kiwi.api.reactive.publisher.SimplePublisher;
import com.github.tix320.skimp.api.check.Try;
import com.github.tix320.skimp.api.generator.IDGenerator;
import com.github.tix320.skimp.api.object.CantorPair;
import com.github.tix320.skimp.api.object.None;
import com.github.tix320.sonder.api.common.communication.*;
import com.github.tix320.sonder.api.common.communication.Headers.HeadersBuilder;
import com.github.tix320.sonder.api.common.event.EventListener;
import com.github.tix320.sonder.api.common.rpc.extra.EndpointExtraArgInjector;
import com.github.tix320.sonder.api.common.rpc.extra.OriginExtraArgExtractor;
import com.github.tix320.sonder.internal.common.rpc.exception.UnsupportedContentTypeException;
import com.github.tix320.sonder.internal.common.rpc.exception.IncompatibleTypeException;
import com.github.tix320.sonder.internal.common.rpc.exception.PathNotFoundException;
import com.github.tix320.sonder.internal.common.rpc.exception.RPCProtocolException;
import com.github.tix320.sonder.internal.common.rpc.exception.RPCRemoteException;
import com.github.tix320.sonder.internal.common.rpc.extra.ExtraArg;
import com.github.tix320.sonder.internal.common.rpc.extra.ExtraArgExtractionException;
import com.github.tix320.sonder.internal.common.rpc.extra.ExtraParam;
import com.github.tix320.sonder.internal.common.rpc.protocol.*;
import com.github.tix320.sonder.internal.common.rpc.service.*;
import com.github.tix320.sonder.internal.common.rpc.service.OriginMethod.ReturnType;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toUnmodifiableMap;

public abstract class RPCProtocol implements Protocol {

	private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

	private TransferTunnel transferTunnel;

	protected EventListener eventListener;

	private final Map<Class<?>, ?> originInstances;

	private final Map<Class<?>, ?> endpointInstances;

	private final Map<Method, OriginMethod> originsByMethod;

	private final Map<String, EndpointMethod> endpointsByPath;

	private final Map<Class<? extends Annotation>, OriginExtraArgExtractor<?, ?>> originExtraArgExtractors;
	private final Map<Class<? extends Annotation>, EndpointExtraArgInjector<?, ?>> endpointExtraArgInjectors;

	private final Map<Long, RequestMetadata> requestMetadataByResponseKey;

	private final Map<Long, RemoteSubscriptionPublisher> remoteSubscriptionPublishers;

	protected final Map<CantorPair, Subscription> realSubscriptions; // [clientId, responseKey] in CantorPair

	private final IDGenerator responseKeyGenerator;

	public RPCProtocol(ProtocolConfig protocolConfig) {
		List<OriginExtraArgExtractor<?, ?>> originExtraArgExtractors = protocolConfig.getOriginExtraArgExtractors();
		this.originExtraArgExtractors = appendBuiltInOriginExtraArgExtractors(originExtraArgExtractors);
		List<EndpointExtraArgInjector<?, ?>> endpointExtraArgInjectors = protocolConfig.getEndpointExtraArgInjectors();
		this.endpointExtraArgInjectors = appendBuiltInEndpointExtraArgInjectors(endpointExtraArgInjectors);

		Map<Class<?>, Object> originInstances = protocolConfig.getOriginInstances();
		Map<Class<?>, Object> endpointInstances = protocolConfig.getEndpointInstances();

		this.originInstances = originInstances;
		this.endpointInstances = endpointInstances;

		OriginRPCMethodResolver originServiceMethods = new OriginRPCMethodResolver(originInstances.keySet(),
				this.originExtraArgExtractors.values()
						.stream()
						.map(OriginExtraArgExtractor::getParamDefinition)
						.collect(Collectors.toList()));

		EndpointRPCMethodResolver endpointServiceMethods = new EndpointRPCMethodResolver(endpointInstances.keySet(),
				this.endpointExtraArgInjectors.values()
						.stream()
						.map(EndpointExtraArgInjector::getParamDefinition)
						.collect(Collectors.toList()));

		this.originsByMethod = originServiceMethods.get()
				.stream()
				.collect(toUnmodifiableMap(ServiceMethod::getRawMethod, identity()));

		this.endpointsByPath = endpointServiceMethods.get()
				.stream()
				.collect(toUnmodifiableMap(ServiceMethod::getPath, identity()));

		this.requestMetadataByResponseKey = new ConcurrentHashMap<>();
		this.remoteSubscriptionPublishers = new ConcurrentHashMap<>();
		this.realSubscriptions = new ConcurrentHashMap<>();
		this.responseKeyGenerator = new IDGenerator(1);
	}

	@Override
	public void init(TransferTunnel transferTunnel, EventListener eventListener) {
		synchronized (this) { // also for memory effects
			this.transferTunnel = transferTunnel;
			this.eventListener = eventListener;
			init();
		}
	}

	protected abstract void init();

	@Override
	public final void handleIncomingTransfer(Transfer transfer) throws IOException {
		Headers headers = transfer.getHeaders();

		TransferType transferType = TransferType.valueOf(headers.getNonNullString(RPCHeaders.TRANSFER_TYPE));

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
	public String getName() {
		return "sonder-RPC";
	}

	@Override
	public void reset() {
		synchronized (this) {
			this.transferTunnel = null;
			this.eventListener = null;

			requestMetadataByResponseKey.values()
					.forEach(requestMetadata -> requestMetadata.getResponsePublisher().complete());
			requestMetadataByResponseKey.clear();

			remoteSubscriptionPublishers.values().forEach(RemoteSubscriptionPublisher::closePublisher);
			remoteSubscriptionPublishers.clear();

			realSubscriptions.values().forEach(Subscription::unsubscribe);
			realSubscriptions.clear();
		}
	}

	@SuppressWarnings("unchecked")
	public <T> T getOrigin(Class<T> clazz) {
		T service = (T) originInstances.get(clazz);
		if (service == null) {
			throw new IllegalArgumentException("Service of " + clazz + " not found");
		}
		return service;
	}

	protected abstract List<OriginExtraArgExtractor<?, ?>> getBuiltInExtractors();

	protected abstract List<EndpointExtraArgInjector<?, ?>> getBuiltInInjectors();

	private Map<Class<? extends Annotation>, OriginExtraArgExtractor<?, ?>> appendBuiltInOriginExtraArgExtractors(
			List<OriginExtraArgExtractor<?, ?>> originExtraArgExtractors) {
		Map<Class<? extends Annotation>, OriginExtraArgExtractor<?, ?>> extraArgExtractorsMap = new HashMap<>();

		for (OriginExtraArgExtractor<?, ?> extraArgExtractor : originExtraArgExtractors) {
			extraArgExtractorsMap.put(extraArgExtractor.getParamDefinition().getAnnotationType(), extraArgExtractor);
		}

		for (OriginExtraArgExtractor<?, ?> extractor : getBuiltInExtractors()) {
			extraArgExtractorsMap.put(extractor.getParamDefinition().getAnnotationType(), extractor);
		}

		return extraArgExtractorsMap;
	}

	private Map<Class<? extends Annotation>, EndpointExtraArgInjector<?, ?>> appendBuiltInEndpointExtraArgInjectors(
			List<EndpointExtraArgInjector<?, ?>> extraArgInjectors) {

		Map<Class<? extends Annotation>, EndpointExtraArgInjector<?, ?>> extraArgInjectorsMap = new HashMap<>();

		for (EndpointExtraArgInjector<?, ?> extraArgInjector : extraArgInjectors) {
			extraArgInjectorsMap.put(extraArgInjector.getParamDefinition().getAnnotationType(), extraArgInjector);
		}

		for (EndpointExtraArgInjector<?, ?> injector : getBuiltInInjectors()) {
			extraArgInjectorsMap.put(injector.getParamDefinition().getAnnotationType(), injector);
		}

		return extraArgInjectorsMap;
	}

	private Object handleOriginCall(OriginMethod method, List<Object> simpleArgs, List<ExtraArg> extraArgs) {
		HeadersBuilder headersBuilder = Headers.builder();

		for (ExtraArg extraArg : extraArgs) {
			Annotation annotation = extraArg.getAnnotation();

			@SuppressWarnings("unchecked")
			OriginExtraArgExtractor<Annotation, Object> extractor = (OriginExtraArgExtractor<Annotation, Object>) originExtraArgExtractors
					.get(annotation.annotationType());

			try {
				Headers headers = extractor.extract(method.getRawMethod(), annotation, extraArg.getValue());
				headersBuilder.headers(headers);
			}
			catch (Throwable e) {
				throw new ExtraArgExtractionException("An error occurred while extracting extra argument", e);
			}
		}

		long responseKey = responseKeyGenerator.next();

		HeadersBuilder builder = headersBuilder.header(RPCHeaders.TRANSFER_TYPE, TransferType.INVOCATION)
				.header(RPCHeaders.PATH, method.getPath())
				.header(RPCHeaders.RESPONSE_KEY, responseKey);

		Long clientId = builder.build().getLong(Headers.DESTINATION_ID);

		Transfer transferToSend;
		switch (method.getRequestDataType()) {
			case ARGUMENTS:
				builder.header(RPCHeaders.CONTENT_TYPE, ContentType.JSON);
				byte[] content = Try.supply(() -> JSON_MAPPER.writeValueAsBytes(simpleArgs))
						.getOrElseThrow(e -> new RPCProtocolException("Cannot convert arguments to JSON", e));
				transferToSend = new StaticTransfer(builder.build(), content);
				break;
			case BINARY:
				builder.header(RPCHeaders.CONTENT_TYPE, ContentType.BINARY);
				transferToSend = new StaticTransfer(builder.build(), (byte[]) simpleArgs.get(0));
				break;
			case TRANSFER:
				builder.header(RPCHeaders.CONTENT_TYPE, ContentType.TRANSFER);
				transferToSend = (Transfer) simpleArgs.get(0);
				transferToSend = new ChannelTransfer(
						transferToSend.getHeaders().compose().headers(builder.build()).build(),
						transferToSend.channel());
				break;
			default:
				throw new IllegalStateException();
		}

		switch (method.getReturnType()) {
			case VOID:
				MonoPublisher<Object> responsePublisher = Publisher.mono();
				requestMetadataByResponseKey.put(responseKey, new RequestMetadata(responsePublisher, method));

				Transfer transfer = transferToSend;
				transferTunnel.send(transfer);
				return null;
			case ASYNC_VALUE:
			case ASYNC_RESPONSE:
				Headers headers = transferToSend.getHeaders().compose().header(RPCHeaders.NEED_RESPONSE, true).build();

				responsePublisher = Publisher.mono();
				requestMetadataByResponseKey.put(responseKey, new RequestMetadata(responsePublisher, method));

				transfer = new ChannelTransfer(headers, transferToSend.channel());
				transferTunnel.send(transfer);
				return responsePublisher.asObservable();
			case SUBSCRIPTION:
				headers = transferToSend.getHeaders().compose().header(RPCHeaders.NEED_RESPONSE, true).build();

				transfer = new ChannelTransfer(headers, transferToSend.channel());

				SimplePublisher<Object> dummyPublisher = Publisher.simple();
				remoteSubscriptionPublishers.put(responseKey, new RemoteSubscriptionPublisher(dummyPublisher, method));

				Observable<Object> observable = dummyPublisher.asObservable();
				DummyObservable dummyObservable = new DummyObservable(observable, clientId, responseKey);

				transferTunnel.send(transfer);
				return dummyObservable;
			default:
				throw new IllegalStateException(String.format("Unknown enum type %s", method.getReturnType()));
		}
	}


	private void processMethodInvocation(Transfer transfer) throws IOException {
		Headers headers = transfer.getHeaders();

		String path = headers.getNonNullString(RPCHeaders.PATH);

		EndpointMethod endpointMethod = endpointsByPath.get(path);
		if (endpointMethod == null) {
			onEndpointPathNotFound(headers);
			throw new PathNotFoundException("Endpoint with path '" + path + "' not found");
		}

		ContentType contentType = ContentType.valueOf(headers.getNonNullString(RPCHeaders.CONTENT_TYPE));

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

		Object[] extraArgs = extractExtraArgs(headers, endpointMethod.getExtraParams(), endpointMethod.getRawMethod());
		Object[] args = mergeArrays(simpleArgs, extraArgs);

		Object serviceInstance = endpointInstances.get(endpointMethod.getRawClass());

		Object result;
		try {
			result = endpointMethod.invoke(serviceInstance, args);
		}
		catch (Exception e) {
			onMethodInvocationException(headers, e);
			throw e;
		}

		boolean needResponse = headers.has(RPCHeaders.NEED_RESPONSE);
		if (needResponse) {
			processEndpointMethodResult(headers, endpointMethod, result);
		}
	}

	private void processErrorResult(Transfer transfer) throws IOException {
		Headers headers = transfer.getHeaders();
		long responseKey = headers.getNonNullLong(RPCHeaders.RESPONSE_KEY);

		Exception exception = extractExceptionFromErrorResult(transfer);

		RequestMetadata requestMetadata = requestMetadataByResponseKey.remove(responseKey);
		if (requestMetadata == null) {
			throw new RPCProtocolException(
					String.format("Request metadata or not found for response key %s", responseKey));
		}
		else {
			MonoPublisher<Object> responsePublisher = requestMetadata.getResponsePublisher();

			OriginMethod originMethod = requestMetadata.getOriginMethod();
			ReturnType returnType = originMethod.getReturnType();

			boolean isAsyncObject = returnType == ReturnType.ASYNC_RESPONSE;

			if (isAsyncObject) {
				responsePublisher.publish(new Response<>(exception, false));
			}
			else {
				new UnhandledErrorResponseException(exception).printStackTrace();
			}
		}
	}

	private void processSubscriptionResult(Transfer transfer) throws IOException {
		Headers headers = transfer.getHeaders();

		SubscriptionActionType subscriptionActionType = SubscriptionActionType.valueOf(
				headers.getNonNullString(RPCHeaders.SUBSCRIPTION_ACTION_TYPE));

		Long sourceId = headers.getLong(Headers.SOURCE_ID);
		long responseKey = headers.getNonNullLong(RPCHeaders.RESPONSE_KEY);

		switch (subscriptionActionType) {
			case UNSUBSCRIBE:
				Subscription subscription = realSubscriptions.remove(
						new CantorPair(sourceId == null ? -1 : sourceId, responseKey));
				if (subscription == null) {
					throw new IllegalStateException(String.format("Subscription not found for key %s", responseKey));
				}
				subscription.unsubscribe();
				break;
			case SUBSCRIPTION_COMPLETED:
				RemoteSubscriptionPublisher remoteSubscriptionPublisher = remoteSubscriptionPublishers.remove(
						responseKey);
				if (remoteSubscriptionPublisher == null) {
					// This may happen, when we are unsubscribe from observable locally, after which completed received
					// System.err.printf("Subscription not found for key %s%n", responseKey);
					return;
				}

				long orderId = headers.getNonNullLong(RPCHeaders.SUBSCRIPTION_RESULT_ORDER_ID);

				remoteSubscriptionPublisher.complete(orderId);
				break;
			case REGULAR_ITEM:
				remoteSubscriptionPublisher = remoteSubscriptionPublishers.get(responseKey);
				if (remoteSubscriptionPublisher == null) {
					// This may happen, when we are unsubscribe from observable locally, but unsubscription still not reached to other end and he send regular value.
					// So we are ignoring this case, just log it
					// System.err.printf("Subscription not found for key %s%n", responseKey);
					return;
				}

				OriginMethod originMethod = remoteSubscriptionPublisher.getOriginMethod();
				if (originMethod.getReturnType() != ReturnType.SUBSCRIPTION) {
					throw new RPCProtocolException(
							String.format("Subscription result was received, but origin method %s(%s) not expect it",
									originMethod.getRawClass().getName(), originMethod.getRawMethod().getName()));
				}

				orderId = headers.getNonNullLong(RPCHeaders.SUBSCRIPTION_RESULT_ORDER_ID);

				JavaType returnJavaType = originMethod.getReturnJavaType();
				Object value = deserializeObject(transfer.channel().readAll(), returnJavaType);
				remoteSubscriptionPublisher.publish(orderId, value);

				break;
			default:
				throw new IllegalStateException();
		}
	}

	private void processInvocationResult(Transfer transfer) throws IOException {
		Headers headers = transfer.getHeaders();

		long responseKey = headers.getNonNullNumber(RPCHeaders.RESPONSE_KEY).longValue();

		RequestMetadata requestMetadata = requestMetadataByResponseKey.remove(responseKey);
		if (requestMetadata == null) {
			throw new RPCProtocolException(String.format("Invalid transfer key `%s`", responseKey));
		}

		OriginMethod originMethod = requestMetadata.getOriginMethod();

		ReturnType returnType = originMethod.getReturnType();
		boolean isAsyncObject = returnType == ReturnType.ASYNC_RESPONSE;

		Publisher<Object> responsePublisher = requestMetadata.getResponsePublisher();

		JavaType returnJavaType = originMethod.getReturnJavaType();
		if (returnJavaType.getRawClass() == None.class) {
			transfer.channel().readRemainingInVain();

			if (isAsyncObject) {
				responsePublisher.publish(new Response<>(None.SELF, true));
			}
			else {
				responsePublisher.publish(None.SELF);
			}
		}
		else {
			ContentType contentType = ContentType.valueOf(headers.getNonNullString(RPCHeaders.CONTENT_TYPE));

			Object result;
			switch (contentType) {
				case BINARY:
					result = transfer.channel().readAll();
					break;
				case JSON:
					if (transfer.channel().getContentLength() == 0) {
						throw new IllegalStateException(
								String.format("Response content is empty, and it cannot be converted to type %s",
										returnJavaType));
					}
					result = deserializeObject(transfer.channel().readAll(), returnJavaType);
					break;
				case TRANSFER:
					result = new StaticTransfer(headers, transfer.channel().readAll());
					break;
				default:
					throw new UnsupportedContentTypeException(contentType);
			}

			try {
				if (isAsyncObject) {
					responsePublisher.publish(new Response<>(result, true));
				}
				else {
					responsePublisher.publish(result);
				}
			}
			catch (ClassCastException e) {
				throw IncompatibleTypeException.forMethodReturnType(originMethod.getRawMethod(), e);
			}
		}
	}

	private Exception extractExceptionFromErrorResult(Transfer transfer) throws IOException {
		Headers headers = transfer.getHeaders();

		byte[] content = transfer.channel().readAll();

		ErrorType errorType = ErrorType.valueOf(headers.getNonNullString(RPCHeaders.ERROR_TYPE));
		switch (errorType) {
			case PATH_NOT_FOUND:
				String path = headers.getNonNullString(RPCHeaders.PATH);
				return new RPCRemoteException(new PathNotFoundException("Endpoint with path '" + path + "' not found"));
			case INCOMPATIBLE_REQUEST:
			case INTERNAL_SERVER_ERROR:
				return new RPCRemoteException(new String(content));
			default:
				return new RPCRemoteException(new RuntimeException("Unknown error"));
		}
	}

	private Object[] extractArgsFromBinaryRequest(Transfer transfer, EndpointMethod endpointMethod) throws IOException {
		Headers headers = transfer.getHeaders();

		List<Param> simpleParams = endpointMethod.getSimpleParams();

		if (simpleParams.size() != 1) {
			String errorMessage = String.format(
					"The content type is %s. Consequently endpoint method %s(%s) must have only one parameter with type byte[]",
					ContentType.BINARY.name(), endpointMethod.getRawMethod().getName(),
					endpointMethod.getRawClass().getName());
			onIncompatibilityRequestAndMethod(headers, Try.supply(() -> JSON_MAPPER.writeValueAsBytes(errorMessage))
					.getOrElseThrow(RPCProtocolException::new));
			throw new RPCProtocolException(errorMessage);
		}

		byte[] data = transfer.channel().readAll();

		return new Object[]{data};
	}

	private Object[] extractArgsFromJsonRequest(Transfer transfer, EndpointMethod endpointMethod) throws IOException {
		Headers headers = transfer.getHeaders();

		List<Param> simpleParams = endpointMethod.getSimpleParams();

		ArrayNode argsNode;
		byte[] content = transfer.channel().readAll();
		try {
			argsNode = JSON_MAPPER.readValue(content, ArrayNode.class);
		}
		catch (IOException e) {
			onIncompatibilityRequestAndMethod(headers, exceptionMessagesToString(e).getBytes());
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
				onIncompatibilityRequestAndMethod(headers, exceptionMessagesToString(e).getBytes());
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

	private Object[] extractExtraArgs(Headers headers, List<ExtraParam> extraParams, Method method) {
		Object[] extraArgs = new Object[extraParams.size()];

		for (int i = 0; i < extraParams.size(); i++) {
			ExtraParam extraParam = extraParams.get(i);

			Annotation annotation = extraParam.getAnnotation();
			Class<? extends Annotation> annotationClass = annotation.annotationType();

			@SuppressWarnings("unchecked")
			EndpointExtraArgInjector<Annotation, ?> extraArgExtractor = (EndpointExtraArgInjector<Annotation, ?>) endpointExtraArgInjectors
					.get(annotationClass);
			try {
				Object extraArg = extraArgExtractor.extract(method, annotation, headers);
				extraArgs[i] = extraArg;
			}
			catch (Exception e) {
				throw new ExtraArgExtractionException("An error occurred while extracting extra argument", e);
			}
		}

		return extraArgs;
	}

	private void processEndpointMethodResult(Headers headers, EndpointMethod endpointMethod, Object result) {
		HeadersBuilder builder = Headers.builder();

		Long sourceId = headers.getLong(Headers.SOURCE_ID);
		long responseKey = headers.getNonNullLong(RPCHeaders.RESPONSE_KEY);

		builder.header(RPCHeaders.TRANSFER_TYPE, TransferType.INVOCATION_RESULT)
				.header(RPCHeaders.RESPONSE_KEY, responseKey)
				.header(Headers.DESTINATION_ID, sourceId);

		switch (endpointMethod.resultType()) {
			case VOID:
				builder.header(RPCHeaders.CONTENT_TYPE, ContentType.BINARY);
				transferTunnel.send(new StaticTransfer(builder.build(), new byte[0]));
				break;
			case OBJECT:
				builder.header(RPCHeaders.CONTENT_TYPE, ContentType.JSON);
				byte[] transferContent = serializeObject(result);
				transferTunnel.send(new StaticTransfer(builder.build(), transferContent));
				break;
			case BINARY:
				builder.header(RPCHeaders.CONTENT_TYPE, ContentType.BINARY);
				transferTunnel.send(new StaticTransfer(builder.build(), (byte[]) result));
				break;
			case TRANSFER:
				builder.header(RPCHeaders.CONTENT_TYPE, ContentType.TRANSFER);
				Transfer resultTransfer = (Transfer) result;
				Headers finalHeaders = resultTransfer.getHeaders().compose().headers(builder.build()).build();
				transferTunnel.send(new ChannelTransfer(finalHeaders, resultTransfer.channel()));
				break;
			case SUBSCRIPTION:
				Observable<?> observable = (Observable<?>) result;

				observable.subscribe(new RealSubscriber(sourceId, responseKey));
				break;
			default:
				throw new IllegalStateException();
		}
	}

	private void onEndpointPathNotFound(Headers headers) {
		try {
			Headers errorHeaders = Headers.builder()
					.header(Headers.DESTINATION_ID, headers.getLong(Headers.SOURCE_ID))
					.header(RPCHeaders.RESPONSE_KEY, headers.getLong(RPCHeaders.RESPONSE_KEY))
					.header(RPCHeaders.ERROR_TYPE, ErrorType.PATH_NOT_FOUND)
					.header(RPCHeaders.PATH, headers.getNonNullString(RPCHeaders.PATH))
					.build();

			sendErrorResult(errorHeaders, new byte[0]);
		}
		catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void onIncompatibilityRequestAndMethod(Headers headers, byte[] content) {
		try {
			Headers errorHeaders = Headers.builder()
					.header(Headers.DESTINATION_ID, headers.getLong(Headers.SOURCE_ID))
					.header(RPCHeaders.RESPONSE_KEY, headers.getLong(RPCHeaders.RESPONSE_KEY))
					.header(RPCHeaders.ERROR_TYPE, ErrorType.INCOMPATIBLE_REQUEST)
					.header(RPCHeaders.PATH, headers.getNonNullString(RPCHeaders.PATH))
					.build();

			sendErrorResult(errorHeaders, content);
		}
		catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void onMethodInvocationException(Headers headers, Exception exception) {
		try {
			Headers errorHeaders = Headers.builder()
					.header(Headers.DESTINATION_ID, headers.getLong(Headers.SOURCE_ID))
					.header(RPCHeaders.RESPONSE_KEY, headers.getNonNullLong(RPCHeaders.RESPONSE_KEY))
					.header(RPCHeaders.ERROR_TYPE, ErrorType.INTERNAL_SERVER_ERROR)
					.header(RPCHeaders.PATH, headers.getNonNullString(RPCHeaders.PATH))
					.build();

			byte[] content = exceptionMessagesToString(exception).getBytes();

			sendErrorResult(errorHeaders, content);
		}
		catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void sendErrorResult(Headers headers, byte[] content) {
		headers = headers.compose().header(RPCHeaders.TRANSFER_TYPE, TransferType.ERROR_RESULT.name()).build();

		Transfer transfer = new StaticTransfer(headers, content);
		transferTunnel.send(transfer);
	}

	private static String exceptionMessagesToString(Throwable e) {
		StringJoiner errorsBuilder = new StringJoiner("\nCaused by: ");
		while (e != null) {
			String message = e.getClass().getName() + ": " + e.getMessage();
			errorsBuilder.add(message);
			e = e.getCause();
		}

		return errorsBuilder.toString();
	}

	private static Object[] mergeArrays(Object[] array1, Object[] array2) {
		Object[] allArgs = new Object[array1.length + array2.length];

		System.arraycopy(array1, 0, allArgs, 0, array1.length);
		System.arraycopy(array2, 0, allArgs, array1.length, array2.length);

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

	public static final class OriginInvocationHandler implements InvocationHandler {

		private RPCProtocol protocolInstance;

		public void initProtocol(RPCProtocol protocolInstance) {
			synchronized (this) { // also for volatile write
				if (this.protocolInstance != null) {
					throw new IllegalStateException();
				}
				this.protocolInstance = protocolInstance;
			}
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) {
			if (method.getDeclaringClass() == Object.class) {
				throw new UnsupportedOperationException("This method does not allowed on origin services");
			}
			if (protocolInstance == null) {
				throw new RPCProtocolException("Protocol was not built fully");
			}

			OriginMethod originMethod = protocolInstance.originsByMethod.get(method);
			List<Param> simpleParams = originMethod.getSimpleParams();
			List<ExtraParam> extraParams = originMethod.getExtraParams();

			List<Object> simpleArgs = new ArrayList<>(simpleParams.size());
			List<ExtraArg> extraArgs = new ArrayList<>(extraParams.size());

			for (Param simpleParam : simpleParams) {
				int index = simpleParam.getIndex();
				simpleArgs.add(args[index]);
			}

			for (ExtraParam extraParam : extraParams) {
				int index = extraParam.getIndex();
				extraArgs.add(new ExtraArg(args[index], extraParam.getAnnotation()));
			}

			return protocolInstance.handleOriginCall(originMethod, simpleArgs, extraArgs);
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
		public void subscribe(Subscriber<? super Object> subscriber) {
			observable.subscribe(new Subscriber<Object>() {
				@Override
				public boolean onSubscribe(Subscription subscription) {
					return subscriber.onSubscribe(subscription);
				}

				@Override
				public boolean onPublish(Object item) {
					return subscriber.onPublish(item);
				}

				@Override
				public void onComplete(CompletionType completionType) {
					remoteSubscriptionPublishers.remove(responseKey);
					subscriber.onComplete(completionType);
					if (completionType == CompletionType.UNSUBSCRIPTION) {
						Headers headers = Headers.builder()
								.header(Headers.DESTINATION_ID, destinationId)
								.header(RPCHeaders.TRANSFER_TYPE, TransferType.SUBSCRIPTION_RESULT)
								.header(RPCHeaders.SUBSCRIPTION_ACTION_TYPE, SubscriptionActionType.UNSUBSCRIBE)
								.header(RPCHeaders.RESPONSE_KEY, responseKey)
								.build();

						Transfer transfer = new StaticTransfer(headers, new byte[0]);
						transferTunnel.send(transfer);
					}
				}
			});
		}
	}

	private final class RealSubscriber implements Subscriber<Object> {

		private final long destinationId;
		private final long responseKey;
		private final IDGenerator orderIdGenerator;

		public RealSubscriber(Long destinationId, long responseKey) {
			this.destinationId = destinationId == null ? -1 : destinationId;
			this.responseKey = responseKey;
			this.orderIdGenerator = new IDGenerator(1);
		}

		@Override
		public boolean onSubscribe(Subscription subscription) {
			realSubscriptions.put(new CantorPair(destinationId, responseKey), subscription);
			return true;
		}

		@Override
		public boolean onPublish(Object item) {
			Headers headers = Headers.builder()
					.header(Headers.DESTINATION_ID, destinationId)
					.header(RPCHeaders.TRANSFER_TYPE, TransferType.SUBSCRIPTION_RESULT)
					.header(RPCHeaders.RESPONSE_KEY, responseKey)
					.header(RPCHeaders.SUBSCRIPTION_ACTION_TYPE, SubscriptionActionType.REGULAR_ITEM)
					.header(RPCHeaders.SUBSCRIPTION_RESULT_ORDER_ID, orderIdGenerator.next())
					.build();

			byte[] content = serializeObject(item);
			Transfer transferToSend = new StaticTransfer(headers, content);

			transferTunnel.send(transferToSend);

			return true;
		}

		@Override
		public void onComplete(CompletionType completionType) {
			if (completionType == CompletionType.SOURCE_COMPLETED) {
				Headers headers = Headers.builder()
						.header(Headers.DESTINATION_ID, destinationId)
						.header(RPCHeaders.TRANSFER_TYPE, TransferType.SUBSCRIPTION_RESULT)
						.header(RPCHeaders.RESPONSE_KEY, responseKey)
						.header(RPCHeaders.SUBSCRIPTION_ACTION_TYPE, SubscriptionActionType.SUBSCRIPTION_COMPLETED)
						.header(RPCHeaders.SUBSCRIPTION_RESULT_ORDER_ID, orderIdGenerator.next())
						.header(RPCHeaders.CONTENT_TYPE, ContentType.BINARY)
						.build();

				Transfer transferToSend = new StaticTransfer(headers, new byte[0]);

				transferTunnel.send(transferToSend);
			}
		}
	}

	private static final class RPCHeaders {
		private static final String RESPONSE_KEY = "response-key";
		private static final String PATH = "path";
		private static final String TRANSFER_TYPE = "transfer-type";
		private static final String ERROR_TYPE = "error-type";
		private static final String NEED_RESPONSE = "need-response";
		private static final String CONTENT_TYPE = "content-type";
		private static final String SUBSCRIPTION_ACTION_TYPE = "subscription-action-type";
		private static final String SUBSCRIPTION_RESULT_ORDER_ID = "subscription_result_order_id";
	}
}
