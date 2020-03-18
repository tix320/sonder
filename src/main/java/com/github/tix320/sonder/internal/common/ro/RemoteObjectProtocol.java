package com.github.tix320.sonder.internal.common.ro;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.WrongMethodTypeException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.github.tix320.kiwi.api.check.Try;
import com.github.tix320.kiwi.api.reactive.ObservableCandidate;
import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.kiwi.api.reactive.property.Property;
import com.github.tix320.kiwi.api.reactive.property.ReadOnlyProperty;
import com.github.tix320.kiwi.api.reactive.publisher.MonoPublisher;
import com.github.tix320.kiwi.api.reactive.publisher.Publisher;
import com.github.tix320.kiwi.api.reactive.stock.ReadOnlyStock;
import com.github.tix320.kiwi.api.reactive.stock.Stock;
import com.github.tix320.kiwi.api.util.IDGenerator;
import com.github.tix320.kiwi.api.util.Reflections;
import com.github.tix320.kiwi.api.util.collection.Tuple;
import com.github.tix320.sonder.api.common.communication.Headers;
import com.github.tix320.sonder.api.common.communication.Protocol;
import com.github.tix320.sonder.api.common.communication.StaticTransfer;
import com.github.tix320.sonder.api.common.communication.Transfer;
import com.github.tix320.sonder.internal.common.BuiltInProtocol;
import com.github.tix320.sonder.internal.common.ro.property.PropertyChangeProxy;
import com.github.tix320.sonder.internal.common.ro.property.StockChangeProxy;
import com.github.tix320.sonder.internal.common.rpc.RPCProtocolException;
import com.github.tix320.sonder.internal.common.util.Threads;

public class RemoteObjectProtocol implements Protocol {

	private static final Lookup LOOKUP = MethodHandles.publicLookup();

	private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

	private final Map<Class<?>, Map<String, MethodMetaData>> methodsByOwnInterfaces;

	private final Publisher<Transfer> outgoingRequests;

	private final Map<String, LocalObject> localObjectsById;

	private final Map<Long, MonoPublisher<Object>> responsePublishers;

	private final Map<String, ObservableCandidate<Object>> remotePropertiesAndStocks;

	private final IDGenerator transferIdGenerator;

	public RemoteObjectProtocol(List<Class<?>> remoteObjectInterfaces) {
		this.methodsByOwnInterfaces = new ConcurrentHashMap<>(resolveInterfaces(remoteObjectInterfaces));
		this.outgoingRequests = Publisher.simple();
		this.localObjectsById = new ConcurrentHashMap<>();
		this.responsePublishers = new ConcurrentHashMap<>();
		this.remotePropertiesAndStocks = new ConcurrentHashMap<>();
		this.transferIdGenerator = new IDGenerator(1);
	}

	@Override
	public void handleIncomingTransfer(Transfer transfer)
			throws IOException {
		Headers headers = transfer.getHeaders();

		Boolean isResult = headers.getBoolean(Headers.IS_RESULT);
		if (isResult == null || !isResult) {
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
		return BuiltInProtocol.RO.getName();
	}

	@Override
	public void close() {
		outgoingRequests.complete();
		methodsByOwnInterfaces.clear();
		localObjectsById.clear();
	}

	public void registerObject(Object object, String identifier) {
		if (localObjectsById.containsKey(identifier)) {
			throw new IllegalStateException(
					String.format("Object with identifier `%s` already registered", identifier));
		}

		Set<Class<?>> interfaces = Reflections.getAllInterfaces(object.getClass());
		for (Class<?> anInterface : interfaces) {
			if (methodsByOwnInterfaces.containsKey(anInterface)) {
				localObjectsById.put(identifier, new LocalObject(object, anInterface));
				return;
			}
		}

		throw new IllegalArgumentException(String.format("No one interface is registered of object %s", object));
	}

	public <T> T getRemoteObject(Class<T> interfacee, String remoteObjectId) {
		if (!interfacee.isInterface()) {
			throw new IllegalArgumentException(String.format("%s is not an interface", interfacee));
		}

		return createRemoteObjectProxy(interfacee, remoteObjectId);
	}

	private void processInvocation(Transfer transfer)
			throws IOException {
		Headers headers = transfer.getHeaders();
		String objectId = headers.getNonNullString(Headers.RO_OBJECT_ID);

		LocalObject localObject = localObjectsById.get(objectId);
		if (localObject == null) {
			throw new RemoteObjectProtocolException(String.format("Object with id `%s` not found", objectId));
		}

		byte[] bytes = transfer.readAll();
		String methodIdentifier = headers.getNonNullString(Headers.METHOD_ID);
		Object[] args = JSON_MAPPER.readValue(bytes, Object[].class);

		Class<?> anInterface = localObject.getInterface();
		Object object = localObject.getObject();
		MethodMetaData methodMetaData = methodsByOwnInterfaces.get(anInterface).get(methodIdentifier);
		MethodReturnType methodReturnType = methodMetaData.getMethodReturnType();
		MethodHandle methodHandle = methodMetaData.getMethodHandle().bindTo(object);

		Threads.runAsync(() -> {
			Object result;
			try {
				result = methodHandle.invokeWithArguments(args);
			}
			catch (WrongMethodTypeException e) {
				throw new RemoteObjectProtocolException(
						String.format("Cannot call method of %s (signature: %s) with received arguments %s",
								object.getClass(), methodHandle.type(), Arrays.toString(args)));
			}
			catch (Throwable throwable) {
				throw new RemoteObjectProtocolException("Unexpected error", throwable);
			}

			if (methodReturnType == MethodReturnType.OBJECT) {
				Headers headersToSend = Headers.builder()
						.header(Headers.DESTINATION_CLIENT_ID, headers.getNonNullNumber(Headers.SOURCE_CLIENT_ID))
						.header(Headers.TRANSFER_KEY, headers.getNonNullNumber(Headers.TRANSFER_KEY))
						.header(Headers.CLASS_NAME, anInterface.getName())
						.header(Headers.METHOD_ID, methodIdentifier)
						.header(Headers.IS_RESULT, true)
						.build();

				sendResult(headersToSend, result);
			}
			else {
				((ObservableCandidate<?>) result).asObservable().subscribe(value -> {
					Headers headersToSend = Headers.builder()
							.header(Headers.DESTINATION_CLIENT_ID, headers.getNonNullNumber(Headers.SOURCE_CLIENT_ID))
							.header(Headers.IS_PROPERTY, headers.getBoolean(Headers.IS_PROPERTY))
							.header(Headers.CLASS_NAME, anInterface.getName())
							.header(Headers.METHOD_ID, methodIdentifier)
							.header(Headers.IS_RESULT, true)
							.build();

					sendResult(headersToSend, value);
				});
			}
		});
	}

	private void processResult(Transfer transfer)
			throws IOException {
		Headers headers = transfer.getHeaders();
		if (headers.has(Headers.IS_PROPERTY)) {
			processPropertyResult(transfer);
		}
		else if (headers.has(Headers.TRANSFER_KEY)) {
			processSimpleObjectResult(transfer);
		}
		else {
			throw new RemoteObjectProtocolException(
					String.format("No one headers specified for result, `%s` `%s`", Headers.IS_PROPERTY,
							Headers.TRANSFER_KEY));

		}
	}

	private void processPropertyResult(Transfer transfer)
			throws IOException {
		Headers headers = transfer.getHeaders();
		String className = headers.getNonNullString(Headers.CLASS_NAME);
		String methodIdentifier = headers.getNonNullString(Headers.METHOD_ID);
		MethodMetaData methodMetaData = methodsByOwnInterfaces.get(Try.supplyOrRethrow(() -> Class.forName(className)))
				.get(methodIdentifier);
		ObservableCandidate<Object> observableCandidate = remotePropertiesAndStocks.get(methodIdentifier);

		MethodReturnType methodReturnType = methodMetaData.getMethodReturnType();

		byte[] bytes = transfer.readAll();
		Object value = JSON_MAPPER.readValue(bytes, methodMetaData.getReturnType());

		switch (methodReturnType) {
			case PROPERTY:
			case READONLY_PROPERTY:
				Threads.runAsync(() -> ((Property<Object>) observableCandidate).set(value));
				break;
			case STOCK:
			case READONLY_STOCK:
				Threads.runAsync(() -> ((Stock<Object>) observableCandidate).add(value));
				break;
			default:
				throw new IllegalStateException(methodReturnType.name());
		}
	}

	private void processSimpleObjectResult(Transfer transfer)
			throws IOException {
		Headers headers = transfer.getHeaders();
		long transferId = headers.getNonNullNumber(Headers.TRANSFER_KEY).longValue();
		MonoPublisher<Object> responsePublisher = responsePublishers.remove(transferId);
		if (responsePublisher == null) {
			throw new RemoteObjectProtocolException(String.format("Invalid transfer key %S", transferId));
		}

		String className = headers.getNonNullString(Headers.CLASS_NAME);
		String methodIdentifier = headers.getNonNullString(Headers.METHOD_ID);

		MethodMetaData method = methodsByOwnInterfaces.get(Try.supplyOrRethrow(() -> Class.forName(className)))
				.get(methodIdentifier);


		byte[] content = transfer.readAll();
		Object result = JSON_MAPPER.readValue(content, method.getReturnType());

		Threads.runAsync(() -> responsePublisher.publish(result));
	}

	private void sendResult(Headers headers, Object result) {
		byte[] content;
		try {
			content = JSON_MAPPER.writeValueAsBytes(result);
		}
		catch (JsonProcessingException e) {
			throw new RPCProtocolException(String.format("Cannot serialize object %s", result));
		}

		Transfer transfer = new StaticTransfer(headers, content);
		outgoingRequests.publish(transfer);
	}

	private static Map<Class<?>, Map<String, MethodMetaData>> resolveInterfaces(List<Class<?>> remoteObjectInterfaces) {
		return remoteObjectInterfaces.stream()
				.peek(RemoteObjectProtocol::checkInterface)
				.collect(Collectors.toMap(clazz -> clazz, RemoteObjectProtocol::resolveInterface));
	}

	private static Map<String, MethodMetaData> resolveInterface(Class<?> remoteObjectInterface) {
		return Arrays.stream(remoteObjectInterface.getDeclaredMethods())
				.collect(Collectors.toMap(RemoteObjectProtocol::generateIdentifierForMethod,
						RemoteObjectProtocol::scanMethod));
	}

	private static MethodMetaData scanMethod(Method method) {
		String methodId = generateIdentifierForMethod(method);
		MethodHandle methodHandle = unreflectMethod(method);
		Tuple<MethodReturnType, JavaType> returnType = resolveReturnType(method);

		return new MethodMetaData(returnType.first(), returnType.second(), methodHandle, methodId);
	}

	private static Tuple<MethodReturnType, JavaType> resolveReturnType(Method method) {
		Type genericReturnType = method.getGenericReturnType();
		if (Property.class.isAssignableFrom(method.getReturnType())) { // Property or Property<...>
			return new Tuple<>(MethodReturnType.PROPERTY, resolveObservableReturnType(genericReturnType));
		}
		else if (ReadOnlyProperty.class.isAssignableFrom(method.getReturnType())) { // Readonly or Readonly<...>
			return new Tuple<>(MethodReturnType.READONLY_PROPERTY, resolveObservableReturnType(genericReturnType));
		}
		else if (Stock.class.isAssignableFrom(method.getReturnType())) { // Stock or Stock<...>
			return new Tuple<>(MethodReturnType.STOCK, resolveObservableReturnType(genericReturnType));
		}
		else if (ReadOnlyStock.class.isAssignableFrom(method.getReturnType())) { // ReadOnlyStock or ReadOnlyStock<...>
			return new Tuple<>(MethodReturnType.READONLY_STOCK, resolveObservableReturnType(genericReturnType));
		}
		else {
			TypeFactory typeFactory = JSON_MAPPER.getTypeFactory();
			return new Tuple<>(MethodReturnType.OBJECT, typeFactory.constructType(genericReturnType));
		}
	}

	private static JavaType resolveObservableReturnType(Type genericReturnType) {
		TypeFactory typeFactory = JSON_MAPPER.getTypeFactory();

		if (genericReturnType instanceof Class) {
			return typeFactory.constructType(Object.class);
		}
		else if (genericReturnType instanceof ParameterizedType) { // Any<...>
			Type argument = ((ParameterizedType) genericReturnType).getActualTypeArguments()[0]; // <...>
			return typeFactory.constructType(argument);
		}
		else {
			throw new IllegalStateException(genericReturnType.getTypeName());
		}
	}

	private static void checkInterface(Class<?> interfacee) {
		if (!interfacee.isInterface()) {
			throw new RemoteObjectProtocolException(String.format("Non interface class was given: %s", interfacee));
		}
	}

	private static MethodHandle unreflectMethod(Method method) {
		try {
			return LOOKUP.unreflect(method);
		}
		catch (IllegalAccessException e) {
			throw new RemoteObjectProtocolException(String.format("Cannot access to %s ", method));
		}
	}

	private static String generateIdentifierForMethod(Method method) {
		StringJoiner stringJoiner = new StringJoiner(":");

		stringJoiner.add(method.getDeclaringClass().getName());
		stringJoiner.add(method.getName());
		stringJoiner.add(method.getReturnType().getName());

		for (Class<?> parameterType : method.getParameterTypes()) {
			stringJoiner.add(parameterType.getName());
		}

		return stringJoiner.toString();
	}

	@SuppressWarnings("unchecked")
	private <T> T createRemoteObjectProxy(Class<T> interfacee, String remoteObjectId) {
		return (T) Proxy.newProxyInstance(interfacee.getClassLoader(), new Class[]{interfacee},
				(proxy, method, args) -> {
					if (method.getDeclaringClass() == Object.class) {
						throw new UnsupportedOperationException("This method does not allowed on origin services");
					}

					String methodIdentifier = generateIdentifierForMethod(method);
					MethodMetaData methodMetaData = methodsByOwnInterfaces.get(interfacee).get(methodIdentifier);

					ObservableCandidate<Object> candidate = remotePropertiesAndStocks.get(methodIdentifier);
					if (candidate != null) {
						return candidate;
					}

					MethodReturnType methodReturnType = methodMetaData.getMethodReturnType();
					if (methodReturnType == MethodReturnType.OBJECT) {
						long transferId = transferIdGenerator.next();
						MonoPublisher<Object> publisher = Publisher.mono();
						responsePublishers.put(transferId, publisher);
						Headers headers = Headers.builder()
								.header(Headers.RO_OBJECT_ID, remoteObjectId)
								.header(Headers.METHOD_ID, methodIdentifier)
								.header(Headers.TRANSFER_KEY, transferId)
								.build();
						Transfer transfer = new StaticTransfer(headers, JSON_MAPPER.writeValueAsBytes(args));
						outgoingRequests.publish(transfer);
						return publisher.asObservable().toMono().get();
					}
					else {
						ObservableCandidate<Object> observableCandidate = resolvePropertyObject(methodMetaData);

						Headers headers = Headers.builder()
								.header(Headers.RO_OBJECT_ID, remoteObjectId)
								.header(Headers.METHOD_ID, methodIdentifier)
								.header(Headers.IS_PROPERTY, true)
								.build();
						Transfer transfer = new StaticTransfer(headers, JSON_MAPPER.writeValueAsBytes(args));
						outgoingRequests.publish(transfer);

						return observableCandidate;
					}
				});
	}

	private ObservableCandidate<Object> resolvePropertyObject(MethodMetaData methodMetaData) {
		MethodReturnType methodReturnType = methodMetaData.getMethodReturnType();

		String methodId = methodMetaData.getMethodId();
		switch (methodReturnType) {
			case PROPERTY:
				Property<Object> property = (Property<Object>) remotePropertiesAndStocks.computeIfAbsent(methodId,
						s -> Property.forObject());
				return new PropertyChangeProxy<>(property, this::onPropertyChange);
			case STOCK:
				Stock<Object> stock = (Stock<Object>) remotePropertiesAndStocks.computeIfAbsent(methodId,
						s -> Stock.forObject());
				return new StockChangeProxy<>(stock, this::onStockChange);
			case READONLY_PROPERTY:
				Property<Object> propertik = (Property<Object>) remotePropertiesAndStocks.computeIfAbsent(methodId,
						s -> Property.forObject());
				return propertik.toReadOnly();
			case READONLY_STOCK:
				Stock<Object> stockik = (Stock<Object>) remotePropertiesAndStocks.computeIfAbsent(methodId,
						s -> Stock.forObject());
				return stockik.toReadOnly();
			default:
				throw new IllegalStateException(String.valueOf(methodReturnType));
		}
	}

	private void onPropertyChange(Object object) {
		throw new UnsupportedOperationException(); // TODO
	}

	private void onStockChange(Object object) {
		throw new UnsupportedOperationException(); // TODO
	}

	private static String subStringUntilNumber(String value) {
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (Character.isDigit(c)) {
				break;
			}
			builder.append(c);
		}
		return builder.toString();
	}
}
