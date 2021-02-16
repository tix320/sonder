package com.github.tix320.sonder.api.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.kiwi.api.reactive.property.Property;
import com.github.tix320.kiwi.api.reactive.property.StateProperty;
import com.github.tix320.sonder.api.common.Client;
import com.github.tix320.sonder.api.common.communication.*;
import com.github.tix320.sonder.api.server.event.ServerEvents;
import com.github.tix320.sonder.api.server.rpc.ServerRPCProtocol;
import com.github.tix320.sonder.api.server.rpc.ServerRPCProtocolBuilder;
import com.github.tix320.sonder.internal.common.State;
import com.github.tix320.sonder.internal.common.communication.Pack;
import com.github.tix320.sonder.internal.server.SocketClientsSelector;

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

	private final SocketClientsSelector clientsSelector;

	private final ServerEvents serverEvents;

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

	SonderServer(InetSocketAddress address, int workersCoreCount, Map<String, ServerSideProtocol> protocols) {
		this.clientsSelector = new SocketClientsSelector(address, workersCoreCount, (clientId, pack) -> {
			Transfer transfer = convertDataPackToTransfer(pack);
			processTransfer(clientId, transfer);
		});
		this.protocols = new ConcurrentHashMap<>(protocols);
		this.serverEvents = new ServerEvents() {
			@Override
			public Observable<Client> newConnections() {
				return clientsSelector.newClients();
			}

			@Override
			public Observable<Client> deadConnections() {
				return clientsSelector.deadClients();
			}
		};
	}

	public void start() throws IOException {
		boolean changed = state.compareAndSetValue(State.INITIAL, State.RUNNING);
		if (!changed) {
			throw new IllegalStateException("Already started");
		}

		clientsSelector.run();
		protocols.forEach((protocolName, protocol) -> initProtocol(protocol));
	}

	public void stop() throws IOException {
		boolean changed = state.compareAndSetValue(State.RUNNING, State.CLOSED);

		if (changed) {
			state.close();
			clientsSelector.close();
		}
	}

	public ServerEvents events() {
		return serverEvents;
	}

	private void initProtocol(ServerSideProtocol protocol) {
		TransferTunnel transferTunnel = ((clientId, transfer) -> {
			transfer = setProtocolHeader(transfer, protocol.getName());
			Pack pack = transferToClientPack(transfer);
			clientsSelector.send(clientId, pack);
		});

		protocol.init(transferTunnel, serverEvents);
	}

	private Transfer setProtocolHeader(Transfer transfer, String protocolName) {
		return new ChannelTransfer(transfer.getHeaders().compose().header(Headers.PROTOCOL, protocolName).build(),
				transfer.channel());
	}

	private Pack transferToClientPack(Transfer transfer) {
		byte[] headers = serializeHeaders(transfer.getHeaders());

		CertainReadableByteChannel channel = transfer.channel();
		return new Pack(headers, channel);
	}

	private byte[] serializeHeaders(Headers headers) {
		byte[] headerBytes;
		try {
			headerBytes = JSON_MAPPER.writeValueAsBytes(headers);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Cannot write JSON", e);
		}

		return headerBytes;
	}

	private Transfer convertDataPackToTransfer(Pack pack) {
		Headers headers;
		try {
			headers = JSON_MAPPER.readValue(pack.getHeaders(), Headers.class);
		} catch (IOException e) {
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
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}
