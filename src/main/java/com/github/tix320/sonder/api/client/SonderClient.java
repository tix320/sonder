package com.github.tix320.sonder.api.client;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tix320.sonder.api.client.event.ConnectionClosedEvent;
import com.github.tix320.sonder.api.common.communication.*;
import com.github.tix320.sonder.api.common.event.EventListener;
import com.github.tix320.sonder.api.common.rpc.RPCProtocolBuilder;
import com.github.tix320.sonder.internal.client.ServerConnection;
import com.github.tix320.sonder.internal.client.rpc.ClientRPCProtocol;
import com.github.tix320.sonder.internal.client.rpc.ClientRPCProtocolBuilder;
import com.github.tix320.sonder.internal.common.communication.Pack;
import com.github.tix320.sonder.internal.common.event.EventDispatcher;

/**
 * Entry point class for your socket client.
 * Provides main functionality for communicating with server.
 *
 * Communication is performed by sending and receiving transfer objects {@link Transfer}.
 * Each transfer is handled by some protocol {@link Protocol}, which will be selected by header of transfer {@link Headers#PROTOCOL}.
 *
 * You can register any protocol by calling method {@link SonderClientBuilder#registerProtocol(Protocol)}.
 * There are some built-in protocols, such as RPC protocol {@link ClientRPCProtocol}.
 *
 * Create client builder by calling method {@link #forAddress}.
 *
 * @author tix320 on 20-Dec-18
 * @see Protocol
 * @see Transfer
 */
public final class SonderClient implements Closeable {

	private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

	private final Map<String, Protocol> protocols;

	private final ServerConnection connection;

	private final EventDispatcher eventDispatcher;

	/**
	 * Prepare client creating for this socket address.
	 *
	 * @param inetSocketAddress socket address to connect.
	 *
	 * @return builder for future configuring.
	 */
	public static SonderClientBuilder forAddress(InetSocketAddress inetSocketAddress) {
		return new SonderClientBuilder(inetSocketAddress);
	}

	public static RPCProtocolBuilder getRPCProtocolBuilder() {
		return new ClientRPCProtocolBuilder();
	}

	SonderClient(ServerConnection connection, Map<String, Protocol> protocols, EventDispatcher eventDispatcher) {
		this.connection = connection;
		this.protocols = Collections.unmodifiableMap(protocols);
		this.eventDispatcher = eventDispatcher;
		protocols.forEach((protocolName, protocol) -> initProtocol(protocol));
	}

	public synchronized void connect() throws IOException {
		connection.connect(pack -> {
			Transfer transfer = convertDataPackToTransfer(pack);
			processTransfer(transfer);
		});

		eventDispatcher.on(ConnectionClosedEvent.class)
				.toMono()
				.subscribe(connectionClosedEvent -> protocols.forEach((protocolName, protocol) -> protocol.reset()));
	}

	public EventListener getEventListener() {
		return eventDispatcher;
	}

	@Override
	public void close() throws IOException {
		boolean closed = connection.close();

		if (closed) {
			protocols.forEach((protocolName, protocol) -> protocol.reset());
		}
	}

	private void initProtocol(Protocol protocol) {
		TransferTunnel transferTunnel = transfer -> {
			transfer = setProtocolHeader(transfer, protocol.getName());
			Pack pack = transferToDataPack(transfer);
			try {
				connection.send(pack);
			}
			catch (IllegalStateException e) {
				throw new IllegalStateException("Sonder Client does not connected or already closed");
			}
		};

		protocol.init(transferTunnel, eventDispatcher);
	}

	private Transfer setProtocolHeader(Transfer transfer, String protocolName) {
		return new ChannelTransfer(transfer.getHeaders().compose().header(Headers.PROTOCOL, protocolName).build(),
				transfer.channel());
	}

	private Pack transferToDataPack(Transfer transfer) {
		byte[] headers;
		try {
			headers = JSON_MAPPER.writeValueAsBytes(transfer.getHeaders());
		}
		catch (JsonProcessingException e) {
			throw new IllegalStateException("Cannot write JSON", e);
		}

		CertainReadableByteChannel channel = transfer.channel();
		return new Pack(headers, channel);
	}

	private Transfer convertDataPackToTransfer(Pack dataPack) {
		Headers headers;
		try {
			headers = JSON_MAPPER.readValue(dataPack.getHeaders(), Headers.class);
		}
		catch (IOException e) {
			throw new IllegalStateException("Cannot parse JSON", e);
		}
		CertainReadableByteChannel channel = dataPack.channel();

		return new ChannelTransfer(headers, channel);
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
