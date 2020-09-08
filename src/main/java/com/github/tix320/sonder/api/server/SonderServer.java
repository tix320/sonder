package com.github.tix320.sonder.api.server;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.kiwi.api.reactive.property.Property;
import com.github.tix320.kiwi.api.reactive.property.StateProperty;
import com.github.tix320.sonder.api.common.communication.*;
import com.github.tix320.sonder.api.common.event.SonderEvent;
import com.github.tix320.sonder.api.common.event.SonderEventDispatcher;
import com.github.tix320.sonder.api.common.rpc.RPCProtocolBuilder;
import com.github.tix320.sonder.internal.common.State;
import com.github.tix320.sonder.internal.common.communication.Pack;
import com.github.tix320.sonder.internal.server.ClientsSelector;
import com.github.tix320.sonder.internal.server.ClientsSelector.ClientPack;
import com.github.tix320.sonder.internal.server.rpc.ServerRPCProtocol;
import com.github.tix320.sonder.internal.server.rpc.ServerRPCProtocolBuilder;

/**
 * Entry point class for your socket server.
 * Provides main functionality for communicating with clients.
 *
 * Communication is performed by sending and receiving transfer objects {@link Transfer}.
 * Each transfer is handled by some protocol {@link Protocol}, which will be selected by header of transfer {@link Headers#PROTOCOL}.
 *
 * You can register any protocol by calling method {@link SonderServerBuilder#registerProtocol(Protocol)}.
 * There are some built-in protocols, such as RPC protocol {@link ServerRPCProtocol}.
 *
 * Create client builder by calling method {@link #forAddress}.
 *
 * @author Tigran.Sargsyan on 11-Dec-18
 * @see Protocol
 * @see Transfer
 */
public final class SonderServer implements Closeable {

	private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

	private final Map<String, Protocol> protocols;

	private final ClientsSelector clientsSelector;

	private final SonderEventDispatcher eventDispatcher;

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

	public static RPCProtocolBuilder getRPCProtocolBuilder() {
		return new ServerRPCProtocolBuilder();
	}

	SonderServer(ClientsSelector clientsSelector, Map<String, Protocol> protocols,
				 SonderEventDispatcher eventDispatcher) {
		this.clientsSelector = clientsSelector;
		this.protocols = new ConcurrentHashMap<>(protocols);
		this.eventDispatcher = eventDispatcher;
		protocols.forEach((protocolName, protocol) -> initProtocol(protocol));
	}

	public void start() throws IOException {
		boolean changed = state.compareAndSetValue(State.INITIAL, State.RUNNING);
		if (!changed) {
			throw new IllegalStateException("Already started");
		}

		clientsSelector.run(clientPack -> {
			Transfer transfer = clientPackToTransfer(clientPack);
			processTransfer(transfer);
		});
	}

	public <T extends SonderEvent> Observable<T> onEvent(Class<T> eventClass) {
		state.checkState(State.INITIAL, State.RUNNING);

		return eventDispatcher.on(eventClass);
	}

	@Override
	public void close() throws IOException {
		boolean changed = state.compareAndSetValue(State.RUNNING, State.CLOSED);

		if (changed) {
			state.close();
			clientsSelector.close();
		}
	}

	private void initProtocol(Protocol protocol) {
		TransferTunnel transferTunnel = transfer -> {
			state.checkState("Sonder Client does not connected or already closed", State.RUNNING);
			transfer = setProtocolHeader(transfer, protocol.getName());
			ClientPack pack = transferToClientPack(transfer);
			clientsSelector.send(pack);
		};

		protocol.init(transferTunnel, eventDispatcher);
	}

	private Transfer setProtocolHeader(Transfer transfer, String protocolName) {
		return new ChannelTransfer(transfer.getHeaders().compose().header(Headers.PROTOCOL, protocolName).build(),
				transfer.channel());
	}

	private ClientPack transferToClientPack(Transfer transfer) {
		long destinationId = transfer.getHeaders().getNonNullLong(Headers.DESTINATION_ID);

		byte[] headers;
		try {
			headers = JSON_MAPPER.writeValueAsBytes(transfer.getHeaders());
		}
		catch (JsonProcessingException e) {
			throw new IllegalStateException("Cannot write JSON", e);
		}

		CertainReadableByteChannel channel = transfer.channel();
		return new ClientPack(destinationId, new Pack(headers, channel));
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
		headers = headers.compose().header(Headers.SOURCE_ID, clientPack.getClientId()).build();
		CertainReadableByteChannel channel = dataPack.channel();

		return new ChannelTransfer(headers, channel);
	}

	private void processTransfer(Transfer transfer) {
		Headers headers = transfer.getHeaders();

		Number destinationId = headers.getNumber(Headers.DESTINATION_ID);
		if (destinationId != null) { // for any client, so we are redirecting without any processing
			clientsSelector.send(transferToClientPack(transfer));
		}
		else {
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
}
