package io.titix.sonder.server;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitlab.tixtix320.kiwi.api.check.Try;
import com.gitlab.tixtix320.kiwi.api.observable.subject.Subject;
import com.gitlab.tixtix320.kiwi.api.util.IDGenerator;
import io.titix.sonder.extra.ClientID;
import io.titix.sonder.internal.*;
import io.titix.sonder.internal.boot.BootException;
import io.titix.sonder.server.internal.ClientsSelector;
import io.titix.sonder.server.internal.EndpointBoot;
import io.titix.sonder.server.internal.OriginBoot;

import static io.titix.sonder.internal.Headers.*;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;


/**
 * @author Tigran.Sargsyan on 11-Dec-18
 */
@SuppressWarnings("Duplicates")
public final class Server {

	private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final ClientsSelector clientsSelector;

	private final Map<Class<?>, ?> originServices;

	private final Map<Class<?>, ?> endpointServices;

	private final Map<Method, OriginMethod> originsByMethod;

	private final Map<String, EndpointMethod> endpointsByPath;

	private final Map<Long, Subject<Object>> resultSubjects;

	private final IDGenerator transferIdGenerator;

	public static Server run(int port, List<String> originPackages, List<String> endpointPackages) {
		OriginBoot originBoot = new OriginBoot(Config.getPackageClasses(originPackages));
		EndpointBoot endpointBoot = new EndpointBoot(Config.getPackageClasses(endpointPackages));

		return new Server(new ClientsSelector(new InetSocketAddress(port)), originBoot, endpointBoot);
	}

	private Server(ClientsSelector clientsSelector, OriginBoot originBoot, EndpointBoot endpointBoot) {

		this.clientsSelector = clientsSelector;
		clientsSelector.requests().subscribe(this::handleTransfer);

		this.originsByMethod = originBoot.getServiceMethods()
				.stream()
				.collect(toMap(ServiceMethod::getRawMethod, identity()));

		this.endpointsByPath = endpointBoot.getServiceMethods()
				.stream()
				.collect(toMap(ServiceMethod::getPath, identity()));

		OriginInvocationHandler.Handler invocationHandler = createOriginInvocationHandler();
		this.originServices = originBoot.getServiceMethods()
				.stream()
				//.peek(this::checkOriginExtraParamTypes)
				.peek(Server::checkDestination)
				.map(ServiceMethod::getRawClass)
				.distinct()
				.collect(toMap(clazz -> clazz, clazz -> createOriginInstance(clazz, invocationHandler)));

		this.endpointServices = endpointBoot.getServiceMethods()
				.stream()
				.map(ServiceMethod::getRawClass)
				.distinct()
				.collect(toMap(clazz -> clazz, this::creatEndpointInstance));

		this.resultSubjects = new ConcurrentHashMap<>();
		this.transferIdGenerator = new IDGenerator();
	}

	@SuppressWarnings("unchecked")
	public <T> T getService(Class<T> clazz) {
		T service = (T) originServices.get(clazz);
		if (service == null) {
			throw new IllegalArgumentException("Service of " + clazz + " not found");
		}
		return service;
	}

	public void stop() {
		try {
			EXECUTOR.shutdownNow();
			clientsSelector.close();
		}
		catch (IOException e) {
			throw new SonderException("Cannot close server socket.", e);
		}
	}

	private void handleTransfer(byte[] data) {
		ObjectNode transfer = (ObjectNode) Try.supplyAndRethrow(() -> MAPPER.readTree(data));
		JsonNode headersNode = transfer.get("headers");
		if (headersNode == null) {
			new IllegalStateException("Headers not provided").printStackTrace();
			return;
		}
		Headers headers = Try.supplyAndRethrow(() -> MAPPER.treeToValue(headersNode, Headers.class));

		Long destinationClientId = headers.getLong(DESTINATION_CLIENT_ID);
		if (destinationClientId == null) {
			new IllegalStateException("Destination client id not provided").printStackTrace();
			return;
		}
		if (destinationClientId != 0L) { // for any client
			JsonNode content = transfer.get("content");
			if (content == null) {
				new IllegalStateException("Content not provided").printStackTrace();
				return;
			}
			ObjectNode transferNode = new ObjectNode(JsonNodeFactory.instance);
			transferNode.set("headers", headersNode);
			transferNode.set("content", content);

			clientsSelector.send(destinationClientId,
					Try.supplyAndRethrow(() -> MAPPER.writeValueAsString(transferNode).getBytes()));
		}
		else { // for server
			String path = headers.getString(PATH);
			EndpointMethod method = endpointsByPath.get(path);
			if (method == null) {
				new PathNotFoundException("Endpoint with path '" + path + "' not found").printStackTrace();
				return;
			}

			Boolean isResponse = headers.getBoolean(IS_RESPONSE);
			if (isResponse) {
				Long transferKey = headers.getLong(TRANSFER_KEY);
				Subject<Object> subject = resultSubjects.get(transferKey);
				if (subject == null) {
					new IllegalStateException(
							String.format("Transfer key %s is invalid", transferKey)).printStackTrace();
					return;
				}
				JsonNode content = transfer.get("content");
				if (content == null) {
					new IllegalStateException("Content not provided").printStackTrace();
					return;
				}
				Class<?> returnType = method.getRawMethod().getReturnType();
				Object result = Try.supplyAndRethrow(() -> MAPPER.treeToValue(content, returnType));
				subject.next(result);
				resultSubjects.remove(transferKey);
			}
			else {
				Long sourceClientId = headers.getLong(SOURCE_CLIENT_ID);
				if (sourceClientId == null) {
					new IllegalStateException("Source client id not provided").printStackTrace();
					return;
				}
				Map<Class<? extends Annotation>, Object> extraArgResolver = Map.of(ClientID.class, sourceClientId);

				Object serviceInstance = endpointServices.get(method.getRawClass());
				ArrayNode argsNode = transfer.withArray("content");
				if (argsNode == null) {
					new IllegalStateException("Arguments not provided").printStackTrace();
					return;
				}

				List<Param> simpleParams = method.getSimpleParams();
				Object[] simpleArgs = new Object[simpleParams.size()];
				for (int i = 0; i < argsNode.size(); i++) {
					JsonNode argNode = argsNode.get(i);
					Param param = simpleParams.get(i);
					simpleArgs[i] = Try.supplyAndRethrow(() -> MAPPER.treeToValue(argNode, param.getType()));
				}
				Object[] args = appendExtraArgs(simpleArgs, method.getExtraParams(),
						annotation -> extraArgResolver.get(annotation.annotationType()));
				Object result = method.invoke(serviceInstance, args);
				Boolean needResponse = headers.getBoolean(NEED_RESPONSE);
				if (needResponse) {
					Headers newHeaders = Headers.builder()
							.header(DESTINATION_CLIENT_ID, sourceClientId)
							.header(TRANSFER_KEY, headers.getLong(TRANSFER_KEY))
							.header(IS_RESPONSE, true)
							.build();
					clientsSelector.send(sourceClientId,
							Try.supplyAndRethrow(() -> MAPPER.writeValueAsBytes(new Transfer<>(newHeaders, result))));
				}
			}
		}
	}

	private static void checkDestination(OriginMethod signature) {
		if (signature.destination == OriginMethod.Destination.SERVER) {
			throw new BootException(String.format(
					"In Server environment origin method '%s' in '%s' must have parameter annotated by @'%s'",
					signature.getRawMethod().getName(), signature.getRawClass(), ClientID.class.getSimpleName()));
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

	private Object createOriginInstance(Class<?> clazz, OriginInvocationHandler.Handler invocationHandler) {
		return Proxy.newProxyInstance(clazz.getClassLoader(), new Class[]{clazz},
				new OriginInvocationHandler(originsByMethod::get, invocationHandler));
	}

	private Object creatEndpointInstance(Class<?> clazz) {
		return Try.supplyAndGet(() -> clazz.getConstructor().newInstance());
	}

	private OriginInvocationHandler.Handler createOriginInvocationHandler() {
		return (method, simpleArgs, extraArgs) -> {
			long clientId = (long) extraArgs.get(ClientID.class).getValue();

			if (method.needResponse) {
				Long transferKey = transferIdGenerator.next();
				Headers headers = Headers.builder().header(PATH, method.getPath())
						.header(DESTINATION_CLIENT_ID, clientId)
						.header(TRANSFER_KEY, transferKey)
						.header(IS_RESPONSE, false)
						.header(NEED_RESPONSE, true)
						.build();
				Subject<Object> resultSubject = Subject.single();
				resultSubjects.put(transferKey, resultSubject);
				clientsSelector.send(headers.getLong(DESTINATION_CLIENT_ID), Try.supplyAndRethrow(
						() -> MAPPER.writeValueAsBytes(new Transfer<>(headers, simpleArgs.toArray()))));
				return resultSubject.asObservable().one();
			}
			else {
				Headers headers = Headers.builder()
						.header(PATH, method.getPath())
						.header(NEED_RESPONSE, false)
						.header(IS_RESPONSE, false)
						.build();
				clientsSelector.send(headers.getLong(DESTINATION_CLIENT_ID),
						Try.supplyAndRethrow(() -> MAPPER.writeValueAsBytes(new Transfer<>(headers, simpleArgs))));
				return null;
			}
		};
	}
}
