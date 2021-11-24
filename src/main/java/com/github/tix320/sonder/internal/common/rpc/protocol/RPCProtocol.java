package com.github.tix320.sonder.internal.common.rpc.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.nio.channels.Channels;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.github.tix320.kiwi.observable.*;
import com.github.tix320.kiwi.publisher.BufferedPublisher;
import com.github.tix320.kiwi.publisher.MonoPublisher;
import com.github.tix320.kiwi.publisher.Publisher;
import com.github.tix320.skimp.api.exception.ExceptionUtils;
import com.github.tix320.skimp.api.generator.IDGenerator;
import com.github.tix320.skimp.api.object.None;
import com.github.tix320.sonder.api.common.communication.*;
import com.github.tix320.sonder.api.common.communication.Headers.HeadersBuilder;
import com.github.tix320.sonder.api.common.rpc.RPCRemoteException;
import com.github.tix320.sonder.api.common.rpc.Response;
import com.github.tix320.sonder.api.common.rpc.extra.EndpointExtraArgInjector;
import com.github.tix320.sonder.api.common.rpc.extra.OriginExtraArgExtractor;
import com.github.tix320.sonder.internal.common.rpc.exception.*;
import com.github.tix320.sonder.internal.common.rpc.extra.ExtraArg;
import com.github.tix320.sonder.internal.common.rpc.extra.ExtraArgExtractionException;
import com.github.tix320.sonder.internal.common.rpc.extra.ExtraParam;
import com.github.tix320.sonder.internal.common.rpc.service.*;
import com.github.tix320.sonder.internal.common.rpc.service.EndpointMethod.ResultType;
import com.github.tix320.sonder.internal.common.rpc.service.OriginMethod.ReturnType;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toUnmodifiableMap;

public abstract class RPCProtocol implements Protocol {

	private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

	private final Map<Class<?>, ?> originInstances;

	private final Map<Class<?>, ?> endpointInstances;

	private final Map<Method, OriginMethod> originsByMethod;

	private final Map<String, EndpointMethod> endpointsByPath;

	private final Map<Class<? extends Annotation>, OriginExtraArgExtractor<?, ?>> originExtraArgExtractors;
	private final Map<Class<? extends Annotation>, EndpointExtraArgInjector<?, ?>> endpointExtraArgInjectors;

	protected final Map<Long, RequestMetadata> requestMetadataByResponseKey;

	protected final Map<Long, RemoteSubscriptionPublisher> remoteSubscriptionPublishers;

	private final IDGenerator responseKeyGenerator;

	public RPCProtocol(RPCProtocolConfig protocolConfig) {
		this.originExtraArgExtractors = protocolConfig.getOriginExtraArgExtractors()
				.stream()
				.collect(toUnmodifiableMap(
						originExtraArgExtractor -> originExtraArgExtractor.getParamDefinition().getAnnotationType(),
						identity()));

		this.endpointExtraArgInjectors = protocolConfig.getEndpointExtraArgInjectors()
				.stream()
				.collect(toUnmodifiableMap(
						originExtraArgExtractor -> originExtraArgExtractor.getParamDefinition().getAnnotationType(),
						identity()));

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
		this.responseKeyGenerator = new IDGenerator(1);
	}

	@Override
	public final String getName() {
		return "sonder-RPC";
	}

	@SuppressWarnings("unchecked")
	public <T> T getOrigin(Class<T> clazz) {
		T service = (T) originInstances.get(clazz);
		if (service == null) {
			throw new IllegalArgumentException("Service of " + clazz + " not found");
		}
		return service;
	}

	protected final void handleIncomingTransfer(Transfer transfer, TransferSender transferSender,
												SubscriptionAdder subscriptionAdder,
												SubscriptionRemover subscriptionRemover) throws IOException {
		Headers headers = transfer.headers();

		TransferType transferType = TransferType.valueOf(headers.getNonNullString(RPCHeaders.TRANSFER_TYPE));

		switch (transferType) {
			case INVOCATION:
				try {
					processMethodInvocation(transfer, transferSender, subscriptionAdder);
				} catch (PathNotFoundException e) {
					Transfer errorTransfer = buildEndpointPathNotFoundErrorTransfer(headers);
					transferSender.send(errorTransfer);
					throw e;
				} catch (BadRequestException e) {
					Transfer errorTransfer = buildIncompatibilityRequestAndMethodErrorTransfer(headers, e);
					transferSender.send(errorTransfer);
					throw e;
				} catch (MethodInvocationException e) {
					Transfer errorTransfer = buildMethodInvocationErrorTransfer(headers, e);
					transferSender.send(errorTransfer);
					throw e;
				}
				break;
			case INVOCATION_RESULT:
				processInvocationResult(transfer);
				break;
			case SUBSCRIPTION_RESULT:
				processSubscriptionResult(transfer, subscriptionRemover);
				break;
			case ERROR_RESPONSE:
				processErrorResponse(transfer);
				break;
			default:
				throw new IllegalStateException();
		}
	}

	protected abstract TransferSender getTransferSenderFromOriginCallHeaders(Headers extraArgHeaders);

	private Object handleOriginCall(OriginMethod method, List<Object> simpleArgs, Headers extraArgHeaders,
									TransferSender transferSender) {
		long responseKey = responseKeyGenerator.next();

		HeadersBuilder builder = extraArgHeaders.compose()
				.header(RPCHeaders.TRANSFER_TYPE, TransferType.INVOCATION)
				.header(RPCHeaders.PATH, method.getPath())
				.header(RPCHeaders.RESPONSE_KEY, responseKey);

		Transfer transferToSend;
		switch (method.getRequestDataType()) {
			case ARGUMENTS:
				builder.header(RPCHeaders.CONTENT_TYPE, ContentType.ARGUMENTS);
				byte[] content;
				try {
					content = JSON_MAPPER.writeValueAsBytes(simpleArgs);
				} catch (JsonProcessingException e) {
					throw new RPCProtocolException("Cannot convert arguments to JSON", e);
				}

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
						transferToSend.headers().compose().headers(builder.build()).build(),
						transferToSend.contentChannel());
				break;
			default:
				throw new IllegalStateException();
		}

		switch (method.getReturnType()) {
			case VOID:
				MonoPublisher<Object> responsePublisher = Publisher.mono();
				requestMetadataByResponseKey.put(responseKey, new RequestMetadata(responsePublisher, method));

				Transfer transfer = transferToSend;
				transferSender.send(transfer);
				return null;
			case ASYNC_VALUE:
			case ASYNC_DUAL_RESPONSE:
				Headers headers = transferToSend.headers().compose().header(RPCHeaders.NEED_RESPONSE, true).build();

				responsePublisher = Publisher.mono();
				requestMetadataByResponseKey.put(responseKey, new RequestMetadata(responsePublisher, method));

				transfer = new ChannelTransfer(headers, transferToSend.contentChannel());
				transferSender.send(transfer);
				return responsePublisher.asObservable();
			case SUBSCRIPTION:
				headers = transferToSend.headers().compose().header(RPCHeaders.NEED_RESPONSE, true).build();

				transfer = new ChannelTransfer(headers, transferToSend.contentChannel());

				requestMetadataByResponseKey.put(responseKey, new RequestMetadata(null, method));

				BufferedPublisher<Object> dummyPublisher = Publisher.buffered();
				remoteSubscriptionPublishers.put(responseKey,
						new RemoteSubscriptionPublisher(dummyPublisher, method.getReturnJavaType()));

				Observable<Object> observable = dummyPublisher.asObservable();
				DummyObservable dummyObservable = new DummyObservable(observable, responseKey, transferSender);

				transferSender.send(transfer);
				return dummyObservable;
			default:
				throw new IllegalStateException(String.format("Unknown enum type %s", method.getReturnType()));
		}
	}

	private void processMethodInvocation(Transfer transfer, TransferSender transferSender,
										 SubscriptionAdder subscriptionAdder) throws IOException {
		Headers headers = transfer.headers();

		String path = headers.getNonNullString(RPCHeaders.PATH);

		EndpointMethod endpointMethod = endpointsByPath.get(path);
		if (endpointMethod == null) {
			throw new PathNotFoundException("Endpoint with path '" + path + "' not found");
		}

		ContentType contentType = ContentType.valueOf(headers.getNonNullString(RPCHeaders.CONTENT_TYPE));

		Object[] simpleArgs;

		switch (contentType) {
			case TRANSFER:
				simpleArgs = extractArgsFromTransferRequest(transfer, endpointMethod);
				break;
			case BINARY:
				simpleArgs = extractArgsFromBinaryRequest(transfer, endpointMethod);
				break;
			case ARGUMENTS:
				simpleArgs = extractArgsFromJsonRequest(transfer, endpointMethod);
				break;
			default:
				throw new IllegalStateException();
		}

		Object[] extraArgs = extractExtraArgs(headers, endpointMethod.getExtraParams(), endpointMethod.getRawMethod());
		Object[] args = mergeArrays(simpleArgs, extraArgs);

		Object serviceInstance = endpointInstances.get(endpointMethod.getRawClass());

		Object result = endpointMethod.invoke(serviceInstance, args);

		boolean needResponse = headers.has(RPCHeaders.NEED_RESPONSE);
		if (needResponse) {
			ResultType resultType = endpointMethod.resultType();
			long responseKey = headers.getNonNullLong(RPCHeaders.RESPONSE_KEY);
			sendEndpointResult(responseKey, resultType, result, transferSender, subscriptionAdder);
		}
	}

	private void processErrorResponse(Transfer transfer) throws IOException {
		Headers headers = transfer.headers();
		long responseKey = headers.getNonNullLong(RPCHeaders.RESPONSE_KEY);

		RPCRemoteException exception = extractExceptionFromErrorResponse(transfer);

		RequestMetadata requestMetadata = requestMetadataByResponseKey.remove(responseKey);
		if (requestMetadata == null) {
			throw new RPCProtocolException(
					String.format("Request metadata not found for response key %s", responseKey));
		} else {
			MonoPublisher<Object> responsePublisher = requestMetadata.getResponsePublisher();

			OriginMethod originMethod = requestMetadata.getOriginMethod();
			ReturnType returnType = originMethod.getReturnType();

			boolean isDualResponse = returnType == ReturnType.ASYNC_DUAL_RESPONSE;

			if (isDualResponse && responsePublisher != null) {
				responsePublisher.publish(new Response<>(exception));
			} else {
				ExceptionUtils.applyToUncaughtExceptionHandler(
						new UnhandledErrorResponseException(originMethod.toString(), exception));
			}
		}
	}

	private void processSubscriptionResult(Transfer transfer, SubscriptionRemover subscriptionRemover) {
		Headers headers = transfer.headers();

		SubscriptionActionType subscriptionActionType = SubscriptionActionType.valueOf(
				headers.getNonNullString(RPCHeaders.SUBSCRIPTION_ACTION_TYPE));

		long responseKey = headers.getNonNullLong(RPCHeaders.RESPONSE_KEY);

		switch (subscriptionActionType) {
			case UNSUBSCRIBE:
				Subscription subscription = subscriptionRemover.remove(responseKey);
				if (subscription == null) {
					throw new IllegalStateException(String.format("Subscription not found for key %s", responseKey));
				}
				subscription.cancel();
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
					try {
						transfer.contentChannel().close();
					} catch (IOException ignored) {
					}
					// This may happen, when we are unsubscribe from observable locally, but unsubscription still not reached to other end and he send regular value.
					// So we are ignoring this case, just log it
					// System.err.printf("Subscription not found for key %s%n", responseKey);
					return;
				}

				orderId = headers.getNonNullLong(RPCHeaders.SUBSCRIPTION_RESULT_ORDER_ID);

				JavaType returnJavaType = remoteSubscriptionPublisher.getItemType();
				Object value = deserializeObject(Channels.newInputStream(transfer.contentChannel()), returnJavaType);
				remoteSubscriptionPublisher.publish(orderId, value);

				break;
			default:
				throw new IllegalStateException();
		}
	}

	private void processInvocationResult(Transfer transfer) throws IOException {
		Headers headers = transfer.headers();

		long responseKey = headers.getNonNullLong(RPCHeaders.RESPONSE_KEY);

		RequestMetadata requestMetadata = requestMetadataByResponseKey.remove(responseKey);
		if (requestMetadata == null) {
			throw new RPCProtocolException(String.format("Invalid transfer key `%s`", responseKey));
		}

		OriginMethod originMethod = requestMetadata.getOriginMethod();

		ReturnType returnType = originMethod.getReturnType();
		boolean isDualResponse = returnType == ReturnType.ASYNC_DUAL_RESPONSE;

		Publisher<Object> responsePublisher = requestMetadata.getResponsePublisher();

		JavaType returnJavaType = originMethod.getReturnJavaType();
		if (returnJavaType.getRawClass() == None.class) {
			transfer.contentChannel().close();

			if (isDualResponse) {
				responsePublisher.publish(new Response<>(None.SELF));
			} else {
				responsePublisher.publish(None.SELF);
			}
		} else {
			ContentType contentType = ContentType.valueOf(headers.getNonNullString(RPCHeaders.CONTENT_TYPE));

			Object result;
			switch (contentType) {
				case BINARY:
					result = transfer.contentChannel().readAllBytes();
					break;
				case ARGUMENTS:
					if (transfer.contentChannel().getContentLength() == 0) {
						throw new IllegalStateException(
								String.format("Response content is empty, and it cannot be converted to type %s",
										returnJavaType));
					}
					result = deserializeObject(Channels.newInputStream(transfer.contentChannel()), returnJavaType);
					break;
				case TRANSFER:
					result = transfer;
					break;
				default:
					throw new UnsupportedContentTypeException(contentType);
			}

			if (isDualResponse) {
				responsePublisher.publish(new Response<>(result));
			} else {
				responsePublisher.publish(result);
			}
		}
	}

	private RPCRemoteException extractExceptionFromErrorResponse(Transfer transfer) throws IOException {
		Headers headers = transfer.headers();

		byte[] content = transfer.contentChannel().readAllBytes();

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
		List<Param> simpleParams = endpointMethod.getSimpleParams();

		if (simpleParams.size() != 1) {
			String errorMessage = String.format(
					"The content type is %s. Consequently endpoint method %s(%s) must have only one parameter with type byte[]",
					ContentType.BINARY.name(), endpointMethod.getRawMethod().getName(),
					endpointMethod.getRawClass().getName());
			throw new BadRequestException(errorMessage);
		}

		byte[] data = transfer.contentChannel().readAllBytes();

		return new Object[]{data};
	}

	private Object[] extractArgsFromJsonRequest(Transfer transfer, EndpointMethod endpointMethod) throws IOException {
		List<Param> simpleParams = endpointMethod.getSimpleParams();

		ArrayNode argsNode;
		byte[] content = transfer.contentChannel().readAllBytes();
		try {
			argsNode = JSON_MAPPER.readValue(content, ArrayNode.class);
		} catch (IOException e) {
			throw new BadRequestException("Cannot parse bytes to ArrayNode", e);
		}

		Object[] simpleArgs = new Object[simpleParams.size()];
		for (int i = 0; i < simpleParams.size(); i++) {
			JsonNode argNode = argsNode.get(i);
			Param param = simpleParams.get(i);
			try {
				simpleArgs[i] = JSON_MAPPER.convertValue(argNode, param.getType());
			} catch (IllegalArgumentException e) {
				throw new BadRequestException(
						String.format("Fail to build object of type `%s` from json %s", param.getType(),
								argsNode.toPrettyString()), e);
			}
		}

		return simpleArgs;
	}

	private Object[] extractArgsFromTransferRequest(Transfer transfer, EndpointMethod endpointMethod) {
		List<Param> simpleParams = endpointMethod.getSimpleParams();

		if (simpleParams.size() != 1) {
			String errorMessage = String.format(
					"The content type is %s. Consequently endpoint method %s(%s) must have only one parameter with type %s",
					ContentType.TRANSFER.name(), endpointMethod.getRawMethod().getName(),
					endpointMethod.getRawClass().getName(), Transfer.class.getName());
			throw new BadRequestException(errorMessage);
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
			} catch (Throwable e) {
				throw new ExtraArgExtractionException("An error occurred while extracting extra argument", e);
			}
		}

		return extraArgs;
	}

	private void sendEndpointResult(long responseKey, ResultType resultType, Object result,
									TransferSender transferSender, SubscriptionAdder subscriptionAdder) {
		HeadersBuilder builder = Headers.builder();

		builder.header(RPCHeaders.TRANSFER_TYPE, TransferType.INVOCATION_RESULT)
				.header(RPCHeaders.RESPONSE_KEY, responseKey);

		switch (resultType) {
			case VOID:
				builder.header(RPCHeaders.CONTENT_TYPE, ContentType.BINARY);
				transferSender.send(new StaticTransfer(builder.build(), new byte[0]));
				break;
			case OBJECT:
				builder.header(RPCHeaders.CONTENT_TYPE, ContentType.ARGUMENTS);
				byte[] transferContent = serializeObject(result);
				transferSender.send(new StaticTransfer(builder.build(), transferContent));
				break;
			case BINARY:
				builder.header(RPCHeaders.CONTENT_TYPE, ContentType.BINARY);
				transferSender.send(new StaticTransfer(builder.build(), (byte[]) result));
				break;
			case TRANSFER:
				builder.header(RPCHeaders.CONTENT_TYPE, ContentType.TRANSFER);
				Transfer resultTransfer = (Transfer) result;
				Headers finalHeaders = resultTransfer.headers().compose().headers(builder.build()).build();
				transferSender.send(new ChannelTransfer(finalHeaders, resultTransfer.contentChannel()));
				break;
			case SUBSCRIPTION:
				@SuppressWarnings("unchecked")
				Observable<Object> observable = (Observable<Object>) result;
				observable.subscribe(new RealSubscriber(responseKey, transferSender, subscriptionAdder));
				break;
			default:
				throw new IllegalStateException();
		}
	}

	private Transfer buildEndpointPathNotFoundErrorTransfer(Headers headers) {
		Headers errorHeaders = Headers.builder()
				.header(RPCHeaders.TRANSFER_TYPE, TransferType.ERROR_RESPONSE.name())
				.header(RPCHeaders.ERROR_TYPE, ErrorType.PATH_NOT_FOUND)
				.header(RPCHeaders.RESPONSE_KEY, headers.getLong(RPCHeaders.RESPONSE_KEY))
				.header(RPCHeaders.PATH, headers.getNonNullString(RPCHeaders.PATH))
				.build();

		return new StaticTransfer(errorHeaders, new byte[0]);
	}

	private Transfer buildIncompatibilityRequestAndMethodErrorTransfer(Headers headers, Throwable throwable) {
		Headers errorHeaders = Headers.builder()
				.header(RPCHeaders.TRANSFER_TYPE, TransferType.ERROR_RESPONSE.name())
				.header(RPCHeaders.ERROR_TYPE, ErrorType.INCOMPATIBLE_REQUEST)
				.header(RPCHeaders.RESPONSE_KEY, headers.getLong(RPCHeaders.RESPONSE_KEY))
				.header(RPCHeaders.PATH, headers.getNonNullString(RPCHeaders.PATH))
				.build();

		String exceptionMessage = RPCProtocol.exceptionMessagesToString(throwable);
		byte[] bytes;
		try {
			bytes = JSON_MAPPER.writeValueAsBytes(exceptionMessage);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException(e);
		}

		return new StaticTransfer(errorHeaders, bytes);
	}

	private Transfer buildMethodInvocationErrorTransfer(Headers headers, Throwable throwable) {
		Headers errorHeaders = Headers.builder()
				.header(RPCHeaders.TRANSFER_TYPE, TransferType.ERROR_RESPONSE.name())
				.header(RPCHeaders.ERROR_TYPE, ErrorType.INTERNAL_SERVER_ERROR)
				.header(RPCHeaders.RESPONSE_KEY, headers.getNonNullLong(RPCHeaders.RESPONSE_KEY))
				.header(RPCHeaders.PATH, headers.getNonNullString(RPCHeaders.PATH))
				.build();

		String exceptionMessage = RPCProtocol.exceptionMessagesToString(throwable);
		byte[] bytes;
		try {
			bytes = JSON_MAPPER.writeValueAsBytes(exceptionMessage);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException(e);
		}

		return new StaticTransfer(errorHeaders, bytes);
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
		} catch (JsonProcessingException e) {
			throw new RPCProtocolException(String.format("Cannot serialize object: %s", object), e);
		}
	}

	private static Object deserializeObject(InputStream inputStream, JavaType expectedType) {
		try {

			return JSON_MAPPER.readValue(inputStream, expectedType);
		} catch (IOException e) {
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

			Headers extraArgHeaders = extraArgsToHeaders(method, extraArgs);

			TransferSender transferSender = protocolInstance.getTransferSenderFromOriginCallHeaders(extraArgHeaders);
			return protocolInstance.handleOriginCall(originMethod, simpleArgs, extraArgHeaders, transferSender);
		}

		private Headers extraArgsToHeaders(Method method, List<ExtraArg> extraArgs) {
			Headers.HeadersBuilder headersBuilder = Headers.builder();
			for (ExtraArg extraArg : extraArgs) {
				Annotation annotation = extraArg.getAnnotation();

				@SuppressWarnings("unchecked")
				OriginExtraArgExtractor<Annotation, Object> extractor = (OriginExtraArgExtractor<Annotation, Object>) protocolInstance.originExtraArgExtractors
						.get(annotation.annotationType());

				try {
					Headers headers = extractor.extract(method, annotation, extraArg.getValue());
					headersBuilder.headers(headers);
				} catch (Throwable e) {
					throw new ExtraArgExtractionException("An error occurred while extracting extra argument", e);
				}
			}

			return headersBuilder.build();
		}
	}

	private final class DummyObservable extends Observable<Object> {

		private final Observable<Object> observable;

		private final long responseKey;

		private final TransferSender transferSender;

		private DummyObservable(Observable<Object> observable, long responseKey, TransferSender transferSender) {
			this.observable = observable;
			this.responseKey = responseKey;
			this.transferSender = transferSender;
		}

		@Override
		public void subscribe(Subscriber<? super Object> subscriber) {
			observable.subscribe(new Subscriber<>(subscriber.getSignalManager()) {
				@Override
				public void onSubscribe(Subscription subscription) {
					 subscriber.setSubscription(subscription);
				}

				@Override
				public void onNext(Object item) {
					subscriber.publish(item);
				}

				@Override
				public void onComplete(Completion completion) {
					remoteSubscriptionPublishers.remove(responseKey);
					requestMetadataByResponseKey.remove(responseKey);
					subscriber.complete(completion);
					if (completion instanceof Unsubscription) {
						Headers headers = Headers.builder()
								.header(RPCHeaders.TRANSFER_TYPE, TransferType.SUBSCRIPTION_RESULT)
								.header(RPCHeaders.SUBSCRIPTION_ACTION_TYPE, SubscriptionActionType.UNSUBSCRIBE)
								.header(RPCHeaders.RESPONSE_KEY, responseKey)
								.build();

						Transfer transfer = new StaticTransfer(headers, new byte[0]);
						transferSender.send(transfer);
					}
				}
			});
		}
	}

	private static final class RealSubscriber extends FlexibleSubscriber<Object> {

		private final long responseKey;
		private final TransferSender transferSender;
		private final SubscriptionAdder subscriptionAdder;
		private final IDGenerator orderIdGenerator;

		public RealSubscriber(long responseKey, TransferSender transferSender, SubscriptionAdder subscriptionAdder) {
			this.responseKey = responseKey;
			this.transferSender = transferSender;
			this.subscriptionAdder = subscriptionAdder;
			this.orderIdGenerator = new IDGenerator(1);
		}

		@Override
		public void onSubscribe(Subscription subscription) {
			subscriptionAdder.add(responseKey, subscription);
			subscription.request(Long.MAX_VALUE);
		}

		@Override
		public void onNext(Object item) {
			Headers headers = Headers.builder()
					.header(RPCHeaders.TRANSFER_TYPE, TransferType.SUBSCRIPTION_RESULT)
					.header(RPCHeaders.RESPONSE_KEY, responseKey)
					.header(RPCHeaders.SUBSCRIPTION_ACTION_TYPE, SubscriptionActionType.REGULAR_ITEM)
					.header(RPCHeaders.SUBSCRIPTION_RESULT_ORDER_ID, orderIdGenerator.next())
					.build();

			byte[] content = serializeObject(item);
			Transfer transferToSend = new StaticTransfer(headers, content);

			transferSender.send(transferToSend);
		}

		@Override
		public void onComplete(Completion completion) {
			if (completion instanceof SourceCompletion) {
				Headers headers = Headers.builder()
						.header(RPCHeaders.TRANSFER_TYPE, TransferType.SUBSCRIPTION_RESULT)
						.header(RPCHeaders.RESPONSE_KEY, responseKey)
						.header(RPCHeaders.SUBSCRIPTION_ACTION_TYPE, SubscriptionActionType.SUBSCRIPTION_COMPLETED)
						.header(RPCHeaders.SUBSCRIPTION_RESULT_ORDER_ID, orderIdGenerator.next())
						.header(RPCHeaders.CONTENT_TYPE, ContentType.BINARY)
						.build();

				Transfer transferToSend = new StaticTransfer(headers, new byte[0]);

				transferSender.send(transferToSend);
			}
		}
	}

	protected interface TransferSender {

		void send(Transfer transfer);
	}

	protected interface SubscriptionAdder {

		void add(long responseKey, Subscription subscription);
	}

	protected interface SubscriptionRemover {

		Subscription remove(long responseKey);
	}

	private static final class RPCHeaders {
		public static final String TRANSFER_TYPE = "transfer-type";
		public static final String PATH = "path";
		public static final String RESPONSE_KEY = "response-key";
		public static final String ERROR_TYPE = "error-type";
		public static final String NEED_RESPONSE = "need-response";
		public static final String CONTENT_TYPE = "content-type";
		public static final String SUBSCRIPTION_ACTION_TYPE = "subscription-action-type";
		public static final String SUBSCRIPTION_RESULT_ORDER_ID = "subscription-result-order-id";
	}
}
