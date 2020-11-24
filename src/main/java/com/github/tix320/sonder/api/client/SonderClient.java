package com.github.tix320.sonder.api.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tix320.skimp.api.thread.LoopThread.BreakLoopException;
import com.github.tix320.skimp.api.thread.Threads;
import com.github.tix320.sonder.api.client.event.ConnectionClosedEvent;
import com.github.tix320.sonder.api.client.event.ConnectionEstablishedEvent;
import com.github.tix320.sonder.api.client.rpc.ClientRPCProtocol;
import com.github.tix320.sonder.api.client.rpc.ClientRPCProtocolBuilder;
import com.github.tix320.sonder.api.common.communication.*;
import com.github.tix320.sonder.api.common.event.EventListener;
import com.github.tix320.sonder.internal.client.ServerConnection;
import com.github.tix320.sonder.internal.common.communication.Pack;
import com.github.tix320.sonder.internal.common.event.EventDispatcher;

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

	private final ServerConnection connection;

	private final EventDispatcher eventDispatcher;

	private final boolean autoReconnect;

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

	SonderClient(ServerConnection connection, Map<String, ClientSideProtocol> protocols,
				 EventDispatcher eventDispatcher, boolean autoReconnect) {
		this.connection = connection;
		this.protocols = Collections.unmodifiableMap(protocols);
		this.eventDispatcher = eventDispatcher;
		this.autoReconnect = autoReconnect;
	}

	public void connect() throws IOException {
		connectToServer();

		eventDispatcher.on(ConnectionClosedEvent.class).toMono().subscribe(connectionClosedEvent -> {
			System.err.println("SONDER: Connection closed.");
			System.err.println("SONDER: Resetting protocols...");
			protocols.forEach((protocolName, protocol) -> protocol.reset());
			if (autoReconnect) {
				System.err.println("SONDER: Auto Reconnect is ON, trying to connect...");
				tryReconnect();
				eventDispatcher.fire(new ConnectionEstablishedEvent());
			}
		});

		protocols.forEach((protocolName, protocol) -> initProtocol(protocol));

		eventDispatcher.fire(new ConnectionEstablishedEvent());
	}

	public void stop() throws IOException {
		boolean closed = connection.close();

		if (closed) {
			protocols.forEach((protocolName, protocol) -> protocol.reset());
		}
	}

	public EventListener getEventListener() {
		return eventDispatcher;
	}

	private void initProtocol(ClientSideProtocol protocol) {
		TransferTunnel transferTunnel = transfer -> {
			transfer = setProtocolHeader(transfer, protocol.getName());
			Pack pack = transferToDataPack(transfer);
			connection.send(pack);
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

	private void processTransfer(Transfer transfer) {
		String protocolName = transfer.getHeaders().getNonNullString(Headers.PROTOCOL);
		ClientSideProtocol protocol = protocols.get(protocolName);
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

	private void connectToServer() throws IOException {
		Consumer<Pack> packConsumer = pack -> {
			Transfer transfer = convertDataPackToTransfer(pack);
			processTransfer(transfer);
		};

		connection.connect(packConsumer);
	}

	private void tryReconnect() {
		AtomicInteger count = new AtomicInteger(1);
		Threads.createLoopDaemonThread(() -> {
			try {
				Thread.sleep(5000);
			}
			catch (InterruptedException e) {
				throw new IllegalStateException(e);
			}
			System.err.printf("SONDER: Try connect %s...%n", count.getAndIncrement());
			try {
				connectToServer();
				System.err.println("SONDER: Connected.");
				throw new BreakLoopException();
			}
			catch (IOException e) {
				e.printStackTrace();
			}
		}).start();
	}
}
