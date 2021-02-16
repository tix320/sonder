package com.github.tix320.sonder.api.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tix320.skimp.api.interval.Interval;
import com.github.tix320.skimp.api.interval.IntervalRepeater;
import com.github.tix320.skimp.api.object.None;
import com.github.tix320.sonder.api.client.event.ClientEvents;
import com.github.tix320.sonder.api.client.rpc.ClientRPCProtocol;
import com.github.tix320.sonder.api.client.rpc.ClientRPCProtocolBuilder;
import com.github.tix320.sonder.api.common.communication.*;
import com.github.tix320.sonder.internal.client.SocketServerConnection;
import com.github.tix320.sonder.internal.common.communication.Pack;

/**
 * Entry point class for your socket client.
 * Provides main functionality for communicating with server.
 *
 * Communication is performed by sending and receiving transfer objects {@link Transfer}.
 * Each transfer is handled by some protocol {@link Protocol}, which will be selected by header of transfer {@link Headers#PROTOCOL}.
 *
 * You can register any protocol by calling method {@link SonderClientBuilder#registerProtocol(ClientSideProtocol)}.
 * There are some built-in protocols, such as RPC protocol {@link ClientRPCProtocol}.
 *
 * Create client builder by calling method {@link #forAddress}.
 *
 * @author tix320 on 20-Dec-18
 * @see Protocol
 * @see Transfer
 */
public final class SonderClient {

	private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

	private final Map<String, ClientSideProtocol> protocols;

	private final SocketServerConnection connection;

	private final ClientEvents clientEvents;

	private final Interval connectInterval;

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

	public static ClientRPCProtocolBuilder getRPCProtocolBuilder() {
		return new ClientRPCProtocolBuilder();
	}

	SonderClient(InetSocketAddress address, Map<String, ClientSideProtocol> protocols, Interval connectInterval) {
		this.connection = new SocketServerConnection(address, pack -> {
			Transfer transfer = convertDataPackToTransfer(pack);
			processTransfer(transfer);
		});
		this.protocols = Collections.unmodifiableMap(protocols);
		this.connectInterval = connectInterval;
		this.clientEvents = connection::state;
	}

	public void connect() throws IOException {
		protocols.forEach((protocolName, protocol) -> initProtocol(protocol));
		if (this.connectInterval == null) {
			connection.connect();

		} else {
			connection.state().conditionalSubscribe(connectionState -> {
				switch (connectionState) {
					case IDLE:
						while (true) {
							Interval interval = this.connectInterval.copyByInitialState();
							boolean connected = tryConnect(interval);
							if (connected) {
								break;
							}
						}
						break;
					case CONNECTED:
						System.out.println("SONDER: Connected.");
						break;
					case CLOSED:
						System.out.println("SONDER: Connection closed.");
						return false;
				}

				return true;
			});

		}
	}

	public void stop() throws IOException {
		boolean closed = connection.close();

		if (closed) {
			protocols.forEach((protocolName, protocol) -> protocol.reset());
		}
	}

	public ClientEvents events() {
		return clientEvents;
	}

	private void initProtocol(ClientSideProtocol protocol) {
		TransferTunnel transferTunnel = transfer -> {
			transfer = setProtocolHeader(transfer, protocol.getName());
			Pack pack = transferToDataPack(transfer);
			connection.send(pack);
		};

		protocol.init(transferTunnel, clientEvents);
	}

	private Transfer setProtocolHeader(Transfer transfer, String protocolName) {
		return new ChannelTransfer(transfer.getHeaders().compose().header(Headers.PROTOCOL, protocolName).build(),
				transfer.channel());
	}

	private Pack transferToDataPack(Transfer transfer) {
		byte[] headers;
		try {
			headers = JSON_MAPPER.writeValueAsBytes(transfer.getHeaders());
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Cannot write JSON", e);
		}

		CertainReadableByteChannel channel = transfer.channel();
		return new Pack(headers, channel);
	}

	private static Transfer convertDataPackToTransfer(Pack pack) {
		Headers headers;
		try {
			headers = JSON_MAPPER.readValue(pack.getHeaders(), Headers.class);
		} catch (IOException e) {
			throw new IllegalStateException("Cannot parse JSON", e);
		}
		CertainReadableByteChannel channel = pack.channel();

		return new ChannelTransfer(headers, channel);
	}

	private void processTransfer(Transfer transfer) {
		String protocolName = transfer.getHeaders().getNonNullString(Headers.PROTOCOL);
		ClientSideProtocol protocol = protocols.get(protocolName);
		if (protocol == null) {
			throw new IllegalStateException(String.format("Protocol %s not found", protocolName));
		}
		try {
			protocol.handleIncomingTransfer(transfer);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private boolean tryConnect(Interval interval) {
		AtomicInteger count = new AtomicInteger(1);
		IntervalRepeater<None> repeater = IntervalRepeater.of(() -> {
			System.out.printf("SONDER: Try connect %s...%n", count.getAndIncrement());
			try {
				connection.connect();
			} catch (IOException e) {
				e.printStackTrace();
				System.err.println("SONDER: Connection fail.");
			}

		}, interval);

		try {
			return repeater.doUntilSuccess(5).isPresent();
		} catch (InterruptedException e) {
			throw new IllegalStateException(e);
		}
	}
}
