package com.gitlab.tixtix320.sonder.api.client;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ValueNode;
import com.gitlab.tixtix320.sonder.api.common.communication.Headers;
import com.gitlab.tixtix320.sonder.api.common.communication.Protocol;
import com.gitlab.tixtix320.sonder.api.common.communication.Transfer;
import com.gitlab.tixtix320.sonder.api.common.topic.Topic;
import com.gitlab.tixtix320.sonder.internal.client.ServerConnection;
import com.gitlab.tixtix320.sonder.internal.client.SocketServerConnection;
import com.gitlab.tixtix320.sonder.internal.client.communication.BuiltInProtocol;
import com.gitlab.tixtix320.sonder.internal.client.rpc.ClientRPCProtocol;
import com.gitlab.tixtix320.sonder.internal.client.topic.ClientTopicProtocol;
import com.gitlab.tixtix320.sonder.internal.common.util.ClassFinder;
import com.gitlab.tixtix320.sonder.internal.server.rpc.ServerRPCProtocol;

import static java.util.stream.Collectors.toMap;

/**
 * @author tix32 on 20-Dec-18
 */
public final class Clonder implements Closeable {

	private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

	private final Map<String, Protocol> protocols;

	private final ServerConnection connection;

	public static Clonder withBuiltInProtocols(String host, int port, String... packagesToScan) {
		List<Class<?>> classes = ClassFinder.getPackageClasses(packagesToScan);
		Map<String, Protocol> protocols = Stream.of(new ClientRPCProtocol(classes), new ClientTopicProtocol())
				.collect(toMap(Protocol::getName, Function.identity()));
		return new Clonder(new SocketServerConnection(new InetSocketAddress(host, port)), protocols);
	}

	public static Clonder withProtocols(String host, int port, Map<String, Protocol> protocols) {
		return new Clonder(new SocketServerConnection(new InetSocketAddress(host, port)), protocols);
	}

	private Clonder(ServerConnection connection, Map<String, Protocol> protocols) {
		this.connection = connection;
		this.protocols = new ConcurrentHashMap<>(protocols);

		connection.incomingRequests().map(this::convertDataPackToTransfer).subscribe(this::processTransfer);
		protocols.forEach((protocolName, protocol) -> protocol.outgoingTransfers().subscribe(transfer -> {
			transfer = new Transfer(transfer.getHeaders().compose().header(Headers.PROTOCOL, protocolName).build(),
					transfer.getContent());

			byte[] data;
			try {
				data = JSON_MAPPER.writeValueAsBytes(transfer);
			}
			catch (JsonProcessingException e) {
				throw new IllegalStateException("Cannot write JSON", e);
			}
			connection.send(data);
		}));
	}

	public void registerProtocol(Protocol protocol) {
		String protocolName = protocol.getName();
		if (BuiltInProtocol.NAMES.contains(protocolName)) {
			throw new IllegalArgumentException(String.format("Protocol name %s is reserved", protocolName));
		}
		if (protocols.containsKey(protocolName)) {
			throw new IllegalStateException(String.format("Protocol %s already registered", protocolName));
		}
		protocols.put(protocolName, protocol);
	}

	/**
	 * Get service from {@link ServerRPCProtocol}
	 *
	 * @return service
	 *
	 * @throws IllegalArgumentException if {@link ServerRPCProtocol} not registered
	 */
	public <T> T getRPCService(Class<T> clazz) {
		Protocol protocol = protocols.get(BuiltInProtocol.RPC.getName());
		if (protocol == null) {
			throw new IllegalStateException("RPC protocol not registered");
		}
		return ((ClientRPCProtocol) protocol).getService(clazz);
	}

	/**
	 * Register topic publisher for {@link ClientTopicProtocol} and return
	 *
	 * @return topic publisher
	 *
	 * @throws IllegalArgumentException if {@link ClientTopicProtocol} not registered
	 */
	public <T> Topic<T> registerTopicPublisher(String topic, TypeReference<T> dataType) {
		Protocol protocol = protocols.get(BuiltInProtocol.TOPIC.getName());
		if (protocol == null) {
			throw new IllegalStateException("Topic protocol not registered");
		}
		return ((ClientTopicProtocol) protocol).registerTopicPublisher(topic, dataType);
	}

	@Override
	public void close()
			throws IOException {
		connection.close();
	}

	private Transfer convertDataPackToTransfer(byte[] dataPack) {
		JsonNode data;
		try {
			data = JSON_MAPPER.readTree(dataPack);
		}
		catch (IOException e) {
			throw new IllegalStateException("Cannot parse JSON", e);
		}

		if (!(data instanceof ObjectNode)) {
			throw new IllegalStateException(String.format("Data must be JSON object, but was %s", data));
		}

		ObjectNode dataObject = (ObjectNode) data;
		JsonNode headers = dataObject.get("headers");
		if (!(headers instanceof ObjectNode)) {
			throw new IllegalStateException(String.format("Headers must be JSON object, but was %s", headers));
		}
		ObjectNode headersObject = (ObjectNode) headers;
		Iterator<Map.Entry<String, JsonNode>> iterator = headersObject.fields();
		Headers.HeadersBuilder builder = Headers.builder();
		while (iterator.hasNext()) {
			Map.Entry<String, JsonNode> entry = iterator.next();
			JsonNode value = entry.getValue();
			if (value instanceof ValueNode) { // ignore non primitive headers
				builder.header(entry.getKey(), JSON_MAPPER.convertValue(value, Object.class));
			}
		}
		JsonNode content = dataObject.get("content");
		return new Transfer(builder.build(), content);
	}

	private void processTransfer(Transfer transfer) {
		String protocolName = transfer.getHeaders().getNonNullString(Headers.PROTOCOL);
		Protocol protocol = protocols.get(protocolName);
		if (protocol == null) {
			throw new IllegalStateException(String.format("Protocol %s not found", protocolName));
		}
		protocol.handleIncomingTransfer(transfer);
	}
}
