package com.github.tix320.sonder.internal.common.rpc.protocol;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.github.tix320.kiwi.api.check.Try;
import com.github.tix320.kiwi.api.reactive.observable.CompletionType;
import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.kiwi.api.reactive.observable.Subscriber;
import com.github.tix320.kiwi.api.reactive.observable.Subscription;
import com.github.tix320.kiwi.api.reactive.publisher.MonoPublisher;
import com.github.tix320.kiwi.api.reactive.publisher.Publisher;
import com.github.tix320.kiwi.api.reactive.publisher.SimplePublisher;
import com.github.tix320.kiwi.api.util.CantorPair;
import com.github.tix320.kiwi.api.util.IDGenerator;
import com.github.tix320.kiwi.api.util.None;
import com.github.tix320.sonder.api.common.communication.*;
import com.github.tix320.sonder.api.common.communication.Headers.HeadersBuilder;
import com.github.tix320.sonder.api.common.rpc.extra.EndpointExtraArgExtractor;
import com.github.tix320.sonder.api.common.rpc.extra.ExtraArg;
import com.github.tix320.sonder.api.common.rpc.extra.OriginExtraArgExtractor;
import com.github.tix320.sonder.api.server.event.ClientConnectionClosedEvent;
import com.github.tix320.sonder.api.server.event.SonderServerEvent;
import com.github.tix320.sonder.internal.common.BuiltInProtocol;
import com.github.tix320.sonder.internal.common.ProtocolOrientation;
import com.github.tix320.sonder.internal.common.communication.UnsupportedContentTypeException;
import com.github.tix320.sonder.internal.common.rpc.IncompatibleTypeException;
import com.github.tix320.sonder.internal.common.rpc.PathNotFoundException;
import com.github.tix320.sonder.internal.common.rpc.RPCProtocolException;
import com.github.tix320.sonder.internal.common.rpc.RPCRemoteException;
import com.github.tix320.sonder.internal.common.rpc.extra.ExtraArgExtractionException;
import com.github.tix320.sonder.internal.common.rpc.extra.ExtraParam;
import com.github.tix320.sonder.internal.common.rpc.extra.extractor.EndpointMethodClientIdExtractor;
import com.github.tix320.sonder.internal.common.rpc.extra.extractor.OriginMethodClientIdExtractor;
import com.github.tix320.sonder.internal.common.rpc.service.*;
import com.github.tix320.sonder.internal.common.rpc.service.OriginMethod.ReturnType;
import com.github.tix320.sonder.internal.common.util.Threads;
import com.github.tix320.sonder.internal.event.SonderEventDispatcher;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toUnmodifiableMap;

public class RPCProtocol implements Protocol {

	private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

	private final ProtocolOrientation orientation;

	private final SonderEventDispatcher<?> sonderEventDispatcher;

	private final Map<Class<?>, ?> originServices;

	private final Map<Class<?>, ?> endpointServices;

	private final Map<Method, OriginMethod> originsByMethod;

	private final Map<String, EndpointMethod> endpointsByPath;

	private final List<OriginExtraArgExtractor<?>> originExtraArgExtractors;
	private final Map<Class<? extends Annotation>, EndpointExtraArgExtractor<?, ?>> endpointExtraArgExtractors;

	private final Map<Long, RequestMetadata> requestMetadataByResponseKey;

	private final Map<Long, RemoteSubscriptionPublisher> remoteSubscriptionPublishers;

	private final Map<CantorPair, Subscription> realSubscriptions; // [clientId, responseKey] in CantorPair

	private final IDGenerator responseKeyGenerator;

	private final Publisher<Transfer> outgoingRequests;

	public RPCProtocol(ProtocolOrientation orientation, SonderEventDispatcher<?> sonderEventDispatcher,
					   List<Class<?>> classes, List<OriginExtraArgExtractor<?>> originExtraArgExtractors,
					   List<EndpointExtraArgExtractor<?, ?>> endpointExtraArgExtractors) {
		this.orientation = orientation;
		this.sonderEventDispatcher = sonderEventDispatcher;

		this.originExtraArgExtractors = appendBuiltInOriginExtraArgExtractors(originExtraArgExtractors);
		this.endpointExtraArgExtractors = appendBuiltInEndpointExtraArgExtractors(endpointExtraArgExtractors);

		OriginRPCServiceMethods originServiceMethods = new OriginRPCServiceMethods(classes,
				this.originExtraArgExtractors.stream()
						.map(OriginExtraArgExtractor::getParamDefinition)
						.collect(Collectors.toList()));

		EndpointRPCServiceMethods endpointServiceMethods = new EndpointRPCServiceMethods(classes,
				this.endpointExtraArgExtractors.values()
						.stream()
						.map(EndpointExtraArgExtractor::getParamDefinition)
						.collect(Collectors.toList()));

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
				.collect(toUnmodifiableMap(clazz -> clazz, this::creatEndpointInstance));

		this.requestMetadataByResponseKey = new ConcurrentHashMap<>();
		this.remoteSubscriptionPublishers = new ConcurrentHashMap<>();
		this.realSubscriptions = new ConcurrentHashMap<>();
		this.responseKeyGenerator = new IDGenerator(1);
		this.outgoingRequests = Publisher.simple();

		listenConnectionCloses();
	}

	@Override
	public final void handleIncomingTransfer(Transfer transfer) throws IOException {
		Headers headers = transfer.getHeaders();

		TransferType transferType = TransferType.valueOf(headers.getNonNullString(Headers.TRANSFER_TYPE));

		try {
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
		finally {
			CertainReadableByteChannel channel = transfer.channel();
			channel.readRemainingInVain();
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

	private Object creatEndpointInstance(Class<?> clazz) {
		return Try.supplyOrRethrow(() -> clazz.getConstructor().newInstance());
	}

	private List<OriginExtraArgExtractor<?>> appendBuiltInOriginExtraArgExtractors(
			List<OriginExtraArgExtractor<?>> extraArgExtractors) {

		extraArgExtractors = new ArrayList<>(extraArgExtractors);

		OriginMethodClientIdExtractor clientIdExtractor = new OriginMethodClientIdExtractor(orientation);
		extraArgExtractors.add(clientIdExtractor);

		return extraArgExtractors;
	}

	private Map<Class<? extends Annotation>, EndpointExtraArgExtractor<?, ?>> appendBuiltInEndpointExtraArgExtractors(
			List<EndpointExtraArgExtractor<?, ?>> extraArgExtractors) {

		Map<Class<? extends Annotation>, EndpointExtraArgExtractor<?, ?>> extraArgExtractorsMap = new HashMap<>();

		for (EndpointExtraArgExtractor<?, ?> extraArgExtractor : extraArgExtractors) {
			extraArgExtractorsMap.put(extraArgExtractor.getParamDefinition().getAnnotationType(), extraArgExtractor);
		}

		EndpointMethodClientIdExtractor clientIdExtractor = new EndpointMethodClientIdExtractor(orientation);
		extraArgExtractorsMap.put(clientIdExtractor.getParamDefinition().getAnnotationType(), clientIdExtractor);

		return extraArgExtractorsMap;
	}

	private Object handleOriginCall(OriginMethod method, List<Object> simpleArgs,
									Map<Class<? extends Annotation>, ExtraArg<?>> extraArgs) {
		HeadersBuilder headersBuilder = Headers.builder();

		for (OriginExtraArgExtractor<?> originExtraArgExtractor : originExtraArgExtractors) {
			Class<?> annotationType = originExtraArgExtractor.getParamDefinition().getAnnotationType();

			@SuppressWarnings("unchecked")
			ExtraArg<Annotation> extraArg = (ExtraArg<Annotation>) extraArgs.get(annotationType);
			if (extraArg == null) {
				continue;
			}

			@SuppressWarnings("unchecked")
			OriginExtraArgExtractor<Annotation> castedExtractor = (OriginExtraArgExtractor<Annotation>) originExtraArgExtractor;

			try {
				Headers headers = castedExtractor.extract(extraArg, method.getRawMethod());
				headersBuilder.headers(headers);
			}
			catch (Exception e) {
				throw new ExtraArgExtractionException("An error occurred while extracting extra argument", e);
			}
		}

		long responseKey = responseKeyGenerator.next();

		Headers.HeadersBuilder builder = headersBuilder.header(Headers.TRANSFER_TYPE, TransferType.INVOCATION)
				.header(Headers.PATH, method.getPath())
				.header(Headers.RESPONSE_KEY, responseKey);

		Long clientId = builder.build().getLong(Headers.DESTINATION_ID);

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
				outgoingRequests.publish(transfer);
				return null;
			case ASYNC_RESPONSE:
				Headers headers = transferToSend.getHeaders().compose().header(Headers.NEED_RESPONSE, true).build();

				responsePublisher = Publisher.mono();
				requestMetadataByResponseKey.put(responseKey, new RequestMetadata(responsePublisher, method));

				transfer = new ChannelTransfer(headers, transferToSend.channel());
				outgoingRequests.publish(transfer);
				return responsePublisher.asObservable();
			case SUBSCRIPTION:
				headers = transferToSend.getHeaders().compose().header(Headers.NEED_RESPONSE, true).build();

				transfer = new ChannelTransfer(headers, transferToSend.channel());

				SimplePublisher<Object> dummyPublisher = Publisher.simple();
				remoteSubscriptionPublishers.put(responseKey, new RemoteSubscriptionPublisher(dummyPublisher, method));

				Observable<Object> observable = dummyPublisher.asObservable();
				DummyObservable dummyObservable = new DummyObservable(observable, clientId, responseKey);

				outgoingRequests.publish(transfer);
				return dummyObservable;
			default:
				throw new IllegalStateException(String.format("Unknown enum type %s", method.getReturnType()));
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

		Object[] extraArgs = extractExtraArgs(headers, endpointMethod.getExtraParams(), endpointMethod.getRawMethod());
		Object[] args = mergeArrays(simpleArgs, extraArgs);

		Object serviceInstance = endpointServices.get(endpointMethod.getRawClass());

		Object result;
		try {
			result = endpointMethod.invoke(serviceInstance, args);
		}
		catch (Exception e) {
			onMethodInvocationException(headers, e);
			throw e;
		}

		boolean needResponse = headers.has(Headers.NEED_RESPONSE);
		if (needResponse) {
			processEndpointMethodResult(headers, endpointMethod, result);
		}
	}

	private void processErrorResult(Transfer transfer) {
		Headers headers = transfer.getHeaders();
		long responseKey = headers.getNonNullLong(Headers.RESPONSE_KEY);

		Exception exception = extractExceptionFromErrorResult(transfer);

		RequestMetadata requestMetadata = requestMetadataByResponseKey.remove(responseKey);
		if (requestMetadata == null) {
			RemoteSubscriptionPublisher remoteSubscriptionPublisher = remoteSubscriptionPublishers.get(responseKey);
			if (remoteSubscriptionPublisher == null) {
				throw new RPCProtocolException(
						String.format("Request metadata or remote subscription not found for response key %s",
								responseKey));
			}
			remoteSubscriptionPublisher.forcePublishError(exception);
		}
		else {
			Publisher<Object> responsePublisher = requestMetadata.getResponsePublisher();

			responsePublisher.publishError(exception);
			responsePublisher.complete();
		}
	}

	private void processSubscriptionResult(Transfer transfer) {
		Headers headers = transfer.getHeaders();

		SubscriptionActionType subscriptionActionType = SubscriptionActionType.valueOf(
				headers.getNonNullString(Headers.SUBSCRIPTION_ACTION_TYPE));

		Long sourceId = headers.getLong(Headers.SOURCE_ID);
		long responseKey = headers.getNonNullLong(Headers.RESPONSE_KEY);

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
					throw new IllegalStateException(String.format("Subscription not found for key %s", responseKey));
				}

				long orderId = headers.getNonNullLong(Headers.SUBSCRIPTION_RESULT_ORDER_ID);

				remoteSubscriptionPublisher.complete(orderId);
				break;
			case REGULAR_ITEM:
			case REGULAR_ERROR:
				remoteSubscriptionPublisher = remoteSubscriptionPublishers.get(responseKey);
				if (remoteSubscriptionPublisher == null) {
					throw new IllegalStateException(String.format("Subscription not found for key %s", responseKey));
				}

				OriginMethod originMethod = remoteSubscriptionPublisher.getOriginMethod();
				if (originMethod.getReturnType() != ReturnType.SUBSCRIPTION) {
					throw new RPCProtocolException(
							String.format("Subscription result was received, but origin method %s(%s) not expect it",
									originMethod.getRawClass().getName(), originMethod.getRawMethod().getName()));
				}

				orderId = headers.getNonNullLong(Headers.SUBSCRIPTION_RESULT_ORDER_ID);

				if (subscriptionActionType == SubscriptionActionType.REGULAR_ITEM) {
					JavaType returnJavaType = originMethod.getReturnJavaType();
					Object value = deserializeObject(Try.supplyOrRethrow(transfer.channel()::readAll), returnJavaType);
					remoteSubscriptionPublisher.publish(orderId, true, value);
				}
				else {
					byte[] content = Try.supplyOrRethrow(transfer.channel()::readAll);
					String errors = new String(content);
					Throwable error = new RPCRemoteException(errors);
					remoteSubscriptionPublisher.publish(orderId, false, error);
				}

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
			Try.runOrRethrow(transfer.channel()::readRemainingInVain);

			Threads.runAsync(() -> responsePublisher.publish(None.SELF));
		}
		else if (transfer.channel().getContentLength() == 0) {
			throw new IllegalStateException(
					String.format("Response content is empty, and it cannot be converted to type %s", returnJavaType));
		}
		else {
			ContentType contentType = headers.getContentType();

			Object result;
			switch (contentType) {
				case BINARY:
					result = Try.supplyOrRethrow(transfer.channel()::readAll);
					break;
				case JSON:
					result = deserializeObject(Try.supplyOrRethrow(transfer.channel()::readAll), returnJavaType);
					break;
				case TRANSFER:
					result = transfer;
					break;
				default:
					throw new UnsupportedContentTypeException(contentType);
			}

			try {
				responsePublisher.publish(result);
			}
			catch (ClassCastException e) {
				throw IncompatibleTypeException.forMethodReturnType(originMethod.getRawMethod(), e);
			}
		}
	}

	private Exception extractExceptionFromErrorResult(Transfer transfer) {
		Headers headers = transfer.getHeaders();

		byte[] content = Try.supplyOrRethrow(transfer.channel()::readAll);

		ErrorType errorType = ErrorType.valueOf(headers.getNonNullString(Headers.ERROR_TYPE));
		switch (errorType) {
			case PATH_NOT_FOUND:
				String path = headers.getNonNullString(Headers.PATH);
				return new RPCRemoteException(new PathNotFoundException("Endpoint with path '" + path + "' not found"));
			case INCOMPATIBLE_REQUEST:
			case INTERNAL_SERVER_ERROR:
				return new RPCRemoteException(new String(content));
			default:
				return new RPCRemoteException(new RuntimeException("Unknown error"));
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

		byte[] data = Try.supplyOrRethrow(transfer.channel()::readAll);

		return new Object[]{data};
	}

	private Object[] extractArgsFromJsonRequest(Transfer transfer, EndpointMethod endpointMethod) {
		Headers headers = transfer.getHeaders();

		List<Param> simpleParams = endpointMethod.getSimpleParams();

		ArrayNode argsNode;
		byte[] content = Try.supplyOrRethrow(transfer.channel()::readAll);
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
			EndpointExtraArgExtractor<Annotation, ?> extraArgExtractor = (EndpointExtraArgExtractor<Annotation, ?>) endpointExtraArgExtractors
					.get(annotationClass);
			try {
				Object extraArg = extraArgExtractor.extract(annotation, headers, method);
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
		long responseKey = headers.getNonNullLong(Headers.RESPONSE_KEY);

		builder.header(Headers.TRANSFER_TYPE, TransferType.INVOCATION_RESULT)
				.header(Headers.RESPONSE_KEY, responseKey)
				.header(Headers.DESTINATION_ID, sourceId);

		switch (endpointMethod.resultType()) {
			case VOID:
				builder.contentType(ContentType.BINARY);
				outgoingRequests.publish(new StaticTransfer(builder.build(), new byte[0]));
				break;
			case OBJECT:
				builder.contentType(ContentType.JSON);
				byte[] transferContent = serializeObject(result);
				outgoingRequests.publish(new StaticTransfer(builder.build(), transferContent));
				break;
			case BINARY:
				builder.contentType(ContentType.BINARY);
				outgoingRequests.publish(new StaticTransfer(builder.build(), (byte[]) result));
				break;
			case TRANSFER:
				builder.contentType(ContentType.TRANSFER);
				Transfer resultTransfer = (Transfer) result;
				Headers finalHeaders = resultTransfer.getHeaders().compose().headers(builder.build()).build();
				outgoingRequests.publish(new ChannelTransfer(finalHeaders, resultTransfer.channel()));
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
					.header(Headers.RESPONSE_KEY, headers.getLong(Headers.RESPONSE_KEY))
					.header(Headers.ERROR_TYPE, ErrorType.PATH_NOT_FOUND)
					.header(Headers.PATH, headers.getNonNullString(Headers.PATH))
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
					.header(Headers.RESPONSE_KEY, headers.getLong(Headers.RESPONSE_KEY))
					.header(Headers.ERROR_TYPE, ErrorType.INCOMPATIBLE_REQUEST)
					.header(Headers.PATH, headers.getNonNullString(Headers.PATH))
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
					.header(Headers.RESPONSE_KEY, headers.getNonNullLong(Headers.RESPONSE_KEY))
					.header(Headers.ERROR_TYPE, ErrorType.INTERNAL_SERVER_ERROR)
					.header(Headers.PATH, headers.getNonNullString(Headers.PATH))
					.build();

			byte[] content = exceptionMessagesToString(exception).getBytes();

			sendErrorResult(errorHeaders, content);
		}
		catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void sendErrorResult(Headers headers, byte[] content) {
		headers = headers.compose().header(Headers.TRANSFER_TYPE, TransferType.ERROR_RESULT.name()).build();

		Transfer transfer = new StaticTransfer(headers, content);
		outgoingRequests.publish(transfer);
	}

	private void listenConnectionCloses() {
		if (orientation == ProtocolOrientation.SERVER) {
			@SuppressWarnings("unchecked")
			SonderEventDispatcher<SonderServerEvent> serverEventDispatcher = (SonderEventDispatcher<SonderServerEvent>) this.sonderEventDispatcher;

			serverEventDispatcher.on(ClientConnectionClosedEvent.class)
					.subscribe(event -> cleanupSubscriptionsOfClient(event.getClientId()));
		}
	}

	private void cleanupSubscriptionsOfClient(long clientIdForDelete) {
		Iterator<Entry<CantorPair, Subscription>> iterator = realSubscriptions.entrySet().iterator();
		while (iterator.hasNext()) {
			Entry<CantorPair, Subscription> entry = iterator.next();
			CantorPair cantorPair = entry.getKey();
			long clientId = cantorPair.first();
			if (clientId == clientIdForDelete) {
				Subscription subscription = entry.getValue();
				subscription.unsubscribe();
				iterator.remove();
			}
		}
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
			Map<Class<? extends Annotation>, ExtraArg<?>> extraArgs = new HashMap<>(extraParams.size());

			for (Param simpleParam : simpleParams) {
				int index = simpleParam.getIndex();
				simpleArgs.add(args[index]);
			}

			for (ExtraParam extraParam : extraParams) {
				int index = extraParam.getIndex();
				extraArgs.put(extraParam.getAnnotation().annotationType(),
						new ExtraArg<>(args[index], extraParam.getAnnotation()));
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
				public boolean onError(Throwable throwable) {
					return subscriber.onError(throwable);
				}

				@Override
				public void onComplete(CompletionType completionType) {
					remoteSubscriptionPublishers.remove(responseKey);
					subscriber.onComplete(completionType);
					if (completionType == CompletionType.UNSUBSCRIPTION) {
						Headers headers = Headers.builder()
								.header(Headers.DESTINATION_ID, destinationId)
								.header(Headers.TRANSFER_TYPE, TransferType.SUBSCRIPTION_RESULT)
								.header(Headers.SUBSCRIPTION_ACTION_TYPE, SubscriptionActionType.UNSUBSCRIBE)
								.header(Headers.RESPONSE_KEY, responseKey)
								.build();

						Transfer transfer = new StaticTransfer(headers, new byte[0]);
						outgoingRequests.publish(transfer);
					}
				}
			});
		}
	}

	public class RealSubscriber implements Subscriber<Object> {

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
		public synchronized boolean onPublish(Object item) {
			Headers headers = Headers.builder()
					.header(Headers.DESTINATION_ID, destinationId)
					.header(Headers.TRANSFER_TYPE, TransferType.SUBSCRIPTION_RESULT)
					.header(Headers.RESPONSE_KEY, responseKey)
					.header(Headers.SUBSCRIPTION_ACTION_TYPE, SubscriptionActionType.REGULAR_ITEM)
					.header(Headers.SUBSCRIPTION_RESULT_ORDER_ID, orderIdGenerator.next())
					.build();

			byte[] content = serializeObject(item);
			Transfer transferToSend = new StaticTransfer(headers, content);

			outgoingRequests.publish(transferToSend);

			return true;
		}

		@Override
		public synchronized boolean onError(Throwable throwable) {
			Headers headers = Headers.builder()
					.header(Headers.DESTINATION_ID, destinationId)
					.header(Headers.TRANSFER_TYPE, TransferType.SUBSCRIPTION_RESULT)
					.header(Headers.RESPONSE_KEY, responseKey)
					.header(Headers.SUBSCRIPTION_ACTION_TYPE, SubscriptionActionType.REGULAR_ERROR)
					.header(Headers.SUBSCRIPTION_RESULT_ORDER_ID, orderIdGenerator.next())
					.build();

			byte[] content = exceptionMessagesToString(throwable).getBytes();
			Transfer transferToSend = new StaticTransfer(headers, content);

			outgoingRequests.publish(transferToSend);

			return true;
		}

		@Override
		public void onComplete(CompletionType completionType) {
			if (completionType == CompletionType.SOURCE_COMPLETED) {
				Headers headers = Headers.builder()
						.header(Headers.DESTINATION_ID, destinationId)
						.header(Headers.TRANSFER_TYPE, TransferType.SUBSCRIPTION_RESULT)
						.header(Headers.RESPONSE_KEY, responseKey)
						.header(Headers.SUBSCRIPTION_ACTION_TYPE, SubscriptionActionType.SUBSCRIPTION_COMPLETED)
						.header(Headers.SUBSCRIPTION_RESULT_ORDER_ID, orderIdGenerator.next())
						.contentType(ContentType.BINARY)
						.build();

				Transfer transferToSend = new StaticTransfer(headers, new byte[0]);

				outgoingRequests.publish(transferToSend);
			}
		}
	}

}
