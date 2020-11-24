package com.github.tix320.sonder.api.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tix320.kiwi.api.reactive.property.Property;
import com.github.tix320.kiwi.api.reactive.property.StateProperty;
import com.github.tix320.sonder.api.common.communication.*;
import com.github.tix320.sonder.api.common.event.EventListener;
import com.github.tix320.sonder.api.server.rpc.ServerRPCProtocol;
import com.github.tix320.sonder.api.server.rpc.ServerRPCProtocolBuilder;
import com.github.tix320.sonder.internal.common.State;
import com.github.tix320.sonder.internal.common.communication.Pack;
import com.github.tix320.sonder.internal.common.event.EventDispatcher;
import com.github.tix320.sonder.internal.server.ClientsSelector;
import com.github.tix320.sonder.internal.server.ClientsSelector.ClientPack;

/**
 * Entry point class for your socket server.
 * Provides main functionality for communicating with clients.
 *
 * Communication is performed by sending and receiving transfer objects {@link Transfer}.
 * Each transfer is handled by some protocol {@link Protocol}, which will be selected by header of transfer {@link Headers#PROTOCOL}.
 *
 * You can register any protocol by calling method {@link SonderServerBuilder#registerProtocol(ServerSideProtocol)}.
 * There are some built-in protocols, such as RPC protocol {@link ServerRPCProtocol}.
 *
 * Create client builder by calling method {@link #forAddress}.
 *
 * @author Tigran.Sargsyan on 11-Dec-18
 * @see Protocol
 * @see Transfer
 */
public final class SonderServer {

	private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

	private final Map<String, ServerSideProtocol> protocols;

	private final ClientsSelector clientsSelector;

	private final EventDispatcher eventDispatcher;

	private final StateProperty<State> state = Property.forState(State.INITIAL);

	/**
	 * Prepare server creating for this socket address.
	 *
	 * @param inetSocketAddress socket address to bind.
	 *
	 * @return builder for future configuring.
	 */
	public static SonderServerBuilder forAddress(InetSocketAddress inetSocketAddress) {
		return new SonderServerBuilder(inetSocketAddress);
	}

	public static ServerRPCProtocolBuilder getRPCProtocolBuilder() {
		return new ServerRPCProtocolBuilder();
	}

	SonderServer(ClientsSelector clientsSelector, Map<String, ServerSideProtocol> protocols,
				 EventDispatcher eventDispatcher) {
		this.clientsSelector = clientsSelector;
		this.protocols = new ConcurrentHashMap<>(protocols);
		this.eventDispatcher = eventDispatcher;
	}

	public void start() throws IOException {
		boolean changed = state.compareAndSetValue(State.INITIAL, State.RUNNING);
		if (!changed) {
			throw new IllegalStateException("Already started");
		}

		clientsSelector.run(clientPack -> {
			Transfer transfer = convertDataPackToTransfer(clientPack.getPack());
			processTransfer(clientPack.getClientId(), transfer);
		});
		protocols.forEach((protocolName, protocol) -> initProtocol(protocol));
	}

	public void stop() throws IOException {
		boolean changed = state.compareAndSetValue(State.RUNNING, State.CLOSED);

		if (changed) {
			state.close();
			clientsSelector.close();
		}
	}

	public EventListener getEventListener() {
		return eventDispatcher;
	}

	private void initProtocol(ServerSideProtocol protocol) {
		TransferTunnel transferTunnel = ((clientId, transfer) -> {
			transfer = setProtocolHeader(transfer, protocol.getName());
			ClientPack pack = transferToClientPack(clientId, transfer);
			clientsSelector.send(pack);
		});

		protocol.init(transferTunnel, eventDispatcher);
	}

	private Transfer setProtocolHeader(Transfer transfer, String protocolName) {
		return new ChannelTransfer(transfer.getHeaders().compose().header(Headers.PROTOCOL, protocolName).build(),
				transfer.channel());
	}

	private ClientPack transferToClientPack(long clientId, Transfer transfer) {
		byte[] headers = serializeHeaders(transfer.getHeaders());

		CertainReadableByteChannel channel = transfer.channel();
		return new ClientPack(clientId, new Pack(headers, channel));
	}

	private byte[] serializeHeaders(Headers headers) {
		byte[] headerBytes;
		try {
			headerBytes = JSON_MAPPER.writeValueAsBytes(headers);
		}
		catch (JsonProcessingException e) {
			throw new IllegalStateException("Cannot write JSON", e);
		}

		return headerBytes;
	}

	private Transfer convertDataPackToTransfer(Pack pack) {
		Headers headers;
		try {
			headers = JSON_MAPPER.readValue(pack.getHeaders(), Headers.class);
		}
		catch (IOException e) {
			throw new IllegalStateException("Cannot parse JSON", e);
		}
		CertainReadableByteChannel channel = pack.channel();

		return new ChannelTransfer(headers, channel);
	}

	private void processTransfer(long clientId, Transfer transfer) {
		String protocolName = transfer.getHeaders().getNonNullString(Headers.PROTOCOL);

		ServerSideProtocol protocol = protocols.get(protocolName);
		if (protocol == null) {
			throw new IllegalStateException(String.format("Protocol %s not found", protocolName));
		}
		try {
			protocol.handleIncomingTransfer(clientId, transfer);
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}
