package com.gitlab.tixtix320.sonder.api.server;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ReadableByteChannel;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitlab.tixtix320.sonder.api.common.communication.ChannelTransfer;
import com.gitlab.tixtix320.sonder.api.common.communication.Headers;
import com.gitlab.tixtix320.sonder.api.common.communication.Protocol;
import com.gitlab.tixtix320.sonder.api.common.communication.Transfer;
import com.gitlab.tixtix320.sonder.api.common.topic.Topic;
import com.gitlab.tixtix320.sonder.internal.common.communication.BuiltInProtocol;
import com.gitlab.tixtix320.sonder.internal.common.communication.Pack;
import com.gitlab.tixtix320.sonder.internal.server.ClientsSelector;
import com.gitlab.tixtix320.sonder.internal.server.rpc.ServerRPCProtocol;
import com.gitlab.tixtix320.sonder.internal.server.topic.ServerTopicProtocol;

/**
 * Entry point class for your server side app.
 * Create server builder by calling method {@link #forAddress}.
 *
 * @author Tigran.Sargsyan on 11-Dec-18
 */
public final class Sonder implements Closeable {

	private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

	private final Map<String, Protocol> protocols;

	private final ClientsSelector clientsSelector;

	public static SonderBuilder forAddress(InetSocketAddress inetSocketAddress) {
		return new SonderBuilder(inetSocketAddress);
	}

	Sonder(ClientsSelector clientsSelector, Map<String, Protocol> protocols) {
		this.clientsSelector = clientsSelector;
		this.protocols = new ConcurrentHashMap<>(protocols);

		clientsSelector.incomingRequests().map(this::clientPackToTransfer).subscribe(this::processTransfer);
		protocols.forEach((protocolName, protocol) -> protocol.outgoingTransfers().subscribe(transfer -> {
			Number destinationClientId = transfer.getHeaders().getNonNullNumber(Headers.DESTINATION_CLIENT_ID);

			transfer = new ChannelTransfer(
					transfer.getHeaders().compose().header(Headers.PROTOCOL, protocolName).build(), transfer.channel(),
					transfer.getContentLength());

			byte[] headers;
			try {
				headers = JSON_MAPPER.writeValueAsBytes(transfer.getHeaders());
			}
			catch (JsonProcessingException e) {
				throw new IllegalStateException("Cannot write JSON", e);
			}

			ReadableByteChannel channel = transfer.channel();

			clientsSelector.send(new ClientsSelector.ClientPack(destinationClientId.longValue(),
					new Pack(headers, channel, transfer.getContentLength())));
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
		return ((ServerRPCProtocol) protocol).getService(clazz);
	}

	/**
	 * Register topic publisher for {@link ServerTopicProtocol} and return
	 *
	 * @return topic publisher
	 *
	 * @throws IllegalStateException if {@link ServerTopicProtocol} not registered
	 */
	public <T> Topic<T> registerTopic(String topic, TypeReference<T> dataType, int bufferSize) {
		Protocol protocol = protocols.get(BuiltInProtocol.TOPIC.getName());
		if (protocol == null) {
			throw new IllegalStateException("Topic protocol not registered");
		}
		return ((ServerTopicProtocol) protocol).registerTopic(topic, dataType, bufferSize);
	}

	public <T> Topic<T> registerTopic(String topic, TypeReference<T> dataType) {
		return registerTopic(topic, dataType, 0);
	}

	@Override
	public void close()
			throws IOException {
		clientsSelector.close();
	}

	private Transfer clientPackToTransfer(ClientsSelector.ClientPack clientPack) {
		Pack dataPack = clientPack.getPack();

		Headers headers;
		try {
			headers = JSON_MAPPER.readValue(dataPack.getHeaders(), Headers.class);
		}
		catch (IOException e) {
			throw new IllegalStateException("Cannot parse JSON", e);
		}
		headers = headers.compose().header(Headers.SOURCE_CLIENT_ID, clientPack.getClientId()).build();
		ReadableByteChannel channel = dataPack.channel();

		return new ChannelTransfer(headers, channel, dataPack.getContentLength());
	}

	private void processTransfer(Transfer transfer) {
		String protocolName = transfer.getHeaders().getNonNullString(Headers.PROTOCOL);

		Protocol protocol = protocols.get(protocolName);
		if (protocol == null) {
			throw new IllegalStateException(String.format("Protocol %s not found", protocolName));
		}
		try {
			protocol.handleIncomingTransfer(transfer);
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}
