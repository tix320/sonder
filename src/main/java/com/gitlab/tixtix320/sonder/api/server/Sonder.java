package com.gitlab.tixtix320.sonder.api.server;

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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ValueNode;
import com.gitlab.tixtix320.sonder.api.common.communication.Headers;
import com.gitlab.tixtix320.sonder.api.common.communication.Protocol;
import com.gitlab.tixtix320.sonder.api.common.communication.Transfer;
import com.gitlab.tixtix320.sonder.internal.common.communication.InvalidHeaderException;
import com.gitlab.tixtix320.sonder.internal.common.util.ClassFinder;
import com.gitlab.tixtix320.sonder.internal.server.ClientsSelector;
import com.gitlab.tixtix320.sonder.internal.server.SocketClientsSelector;
import com.gitlab.tixtix320.sonder.internal.server.rpc.ServerRPCProtocol;
import com.gitlab.tixtix320.sonder.internal.server.topic.ServerTopicProtocol;

import static java.util.stream.Collectors.toMap;

/**
 * @author Tigran.Sargsyan on 11-Dec-18
 */
public final class Sonder implements Closeable {

	private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

	private final Map<String, Protocol> protocols;

	private final ClientsSelector clientsSelector;

	public static Sonder withBuiltInProtocols(int port, String... packagesToScan) {
		List<Class<?>> classes = ClassFinder.getPackageClasses(packagesToScan);
		Map<String, Protocol> protocols = Stream.of(new ServerRPCProtocol(classes), new ServerTopicProtocol())
				.collect(toMap(Protocol::getName, Function.identity()));
		return new Sonder(new SocketClientsSelector(new InetSocketAddress(port)), protocols);
	}

	public static Sonder withProtocols(int port, Map<String, Protocol> protocols) {
		return new Sonder(new SocketClientsSelector(new InetSocketAddress(port)), protocols);
	}

	private Sonder(ClientsSelector clientsSelector, Map<String, Protocol> protocols) {
		this.clientsSelector = clientsSelector;
		this.protocols = new ConcurrentHashMap<>(protocols);

		clientsSelector.requests().map(this::clientPackToTransfer).subscribe(this::processTransfer);
		protocols.forEach((protocolName, protocol) -> protocol.transfers().subscribe(transfer -> {
			Object destinationClientId = transfer.getHeaders().get(Headers.DESTINATION_CLIENT_ID);
			if (!(destinationClientId instanceof Number)) {
				throw new InvalidHeaderException(Headers.DESTINATION_CLIENT_ID, destinationClientId, Number.class);
			}
			transfer = new Transfer(transfer.getHeaders().compose().header(Headers.PROTOCOL, protocolName).build(),
					transfer.getContent());
			try {
				clientsSelector.send(new ClientsSelector.ClientPack(((Number) destinationClientId).longValue(),
						JSON_MAPPER.writeValueAsBytes(transfer)));
			}
			catch (JsonProcessingException e) {
				throw new IllegalStateException("Cannot write JSON", e);
			}
		}));
	}

	public void registerProtocol(Protocol protocol) {
		String protocolName = protocol.getName();
		if (protocols.containsKey(protocolName)) {
			throw new IllegalArgumentException(String.format("Protocol %s already exists", protocolName));
		}
		protocols.put(protocolName, protocol);
	}

	/**
	 * Get service from {@link ServerRPCProtocol}
	 *
	 * @return service
	 * @throws IllegalArgumentException if {@link ServerRPCProtocol} not registered
	 */
	public <T> T getRPCService(Class<T> clazz) {
		Protocol protocol = protocols.get("sonder-RPC");
		if (!(protocol instanceof ServerRPCProtocol)) {
			throw new IllegalArgumentException(String.format("Protocol %s not registered", protocol.getName()));
		}
		return ((ServerRPCProtocol) protocol).getService(clazz);
	}

	@Override
	public void close() throws IOException {
		clientsSelector.close();
	}

	private Transfer clientPackToTransfer(ClientsSelector.ClientPack clientPack) {
		JsonNode data;
		try {
			data = JSON_MAPPER.readTree(clientPack.getData());
		}
		catch (IOException e) {
			throw new IllegalStateException("Cannot parse JSON", e);
		}
		if (!(data instanceof ObjectNode)) {
			throw new IllegalStateException(String.format("data must be JSON object, but was %s", data));
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
		builder.header(Headers.SOURCE_CLIENT_ID, clientPack.getClientId());
		JsonNode content = dataObject.get("content");
		return new Transfer(builder.build(), content);
	}

	private void processTransfer(Transfer transfer) {
		Object protocolName = transfer.getHeaders().get(Headers.PROTOCOL);
		if (!(protocolName instanceof String)) {
			throw new InvalidHeaderException(Headers.PROTOCOL, protocolName, String.class);
		}
		Protocol protocol = protocols.get(protocolName);
		if (protocol == null) {
			throw new IllegalStateException(String.format("Protocol %s not found", protocolName));
		}
		protocol.handleTransfer(transfer);
	}
}
