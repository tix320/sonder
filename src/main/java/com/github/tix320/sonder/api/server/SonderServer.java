package com.github.tix320.sonder.api.server;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.channels.ReadableByteChannel;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tix320.kiwi.api.check.Try;
import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.sonder.api.common.communication.*;
import com.github.tix320.sonder.api.common.topic.Topic;
import com.github.tix320.sonder.api.server.event.SonderServerEvent;
import com.github.tix320.sonder.internal.common.BuiltInProtocol;
import com.github.tix320.sonder.internal.common.communication.Pack;
import com.github.tix320.sonder.internal.common.communication.SonderRemoteException;
import com.github.tix320.sonder.internal.common.rpc.RPCProtocol;
import com.github.tix320.sonder.internal.event.SonderEventDispatcher;
import com.github.tix320.sonder.internal.server.ClientsSelector;
import com.github.tix320.sonder.internal.server.ClientsSelector.ClientPack;
import com.github.tix320.sonder.internal.server.topic.ServerTopicProtocol;

/**
 * Entry point class for your socket server.
 * Provides main functionality for communicating with clients.
 *
 * Communication is performed by sending and receiving transfer objects {@link Transfer}.
 * Each transfer is handled by some protocol {@link Protocol}, which will be selected by header of transfer {@link Headers#PROTOCOL}.
 *
 * You can register any protocol by calling method {@link #registerProtocol(Protocol)}.
 * There are some built-in protocols {@link BuiltInProtocol}, which names is reserved and cannot be used.
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

	SonderServer(ClientsSelector clientsSelector, Map<String, Protocol> protocols,
				 SonderEventDispatcher eventDispatcher) {
		this.clientsSelector = clientsSelector;
		this.protocols = new ConcurrentHashMap<>(protocols);
		this.eventDispatcher = eventDispatcher;

		clientsSelector.incomingRequests().map(this::clientPackToTransfer).subscribe(this::processTransfer);
		protocols.forEach((protocolName, protocol) -> listenProtocol(protocol));
	}

	/**
	 * Register protocol for this server.
	 *
	 * @param protocol to register
	 *
	 * @throws IllegalArgumentException if reserved protocol name is used
	 * @throws IllegalStateException    if there are already registered protocol with same name
	 * @see Protocol
	 */
	public void registerProtocol(Protocol protocol) {
		String protocolName = protocol.getName();
		if (BuiltInProtocol.NAMES.contains(protocolName)) {
			throw new IllegalArgumentException(String.format("Protocol name %s is reserved", protocolName));
		}
		if (protocols.containsKey(protocolName)) {
			throw new IllegalStateException(String.format("Protocol %s already registered", protocolName));
		}

		listenProtocol(protocol);

		protocols.put(protocolName, protocol);
	}

	/**
	 * Get service from protocol {@link RPCProtocol}
	 *
	 * @param clazz class of service
	 * @param <T>   of class
	 *
	 * @return service
	 *
	 * @throws IllegalStateException if {@link RPCProtocol} not registered
	 * @see RPCProtocol
	 */
	public <T> T getRPCService(Class<T> clazz) {
		Protocol protocol = protocols.get(BuiltInProtocol.RPC.getName());
		if (protocol == null) {
			throw new IllegalStateException("RPC protocol not registered");
		}
		return ((RPCProtocol) protocol).getService(clazz);
	}

	/**
	 * Register topic for protocol {@link ServerTopicProtocol}
	 *
	 * @param topic      name
	 * @param dataType   which will be used while transferring data in topic
	 * @param bufferSize for buffering last received data
	 * @param <T>        type of topic data
	 *
	 * @return topic
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

	/**
	 * * Register topic for protocol {@link ServerTopicProtocol}
	 * Invokes {@link #registerTopic(String, TypeReference, int)} with '0' value buffer size
	 *
	 * @param <T>      type of topic data
	 * @param topic    name
	 * @param dataType which will be used while transferring data in topic
	 *
	 * @return topic {@link Topic}
	 */
	public <T> Topic<T> registerTopic(String topic, TypeReference<T> dataType) {
		return registerTopic(topic, dataType, 0);
	}

	public <T extends SonderServerEvent> Observable<T> onEvent(Class<T> eventClass) {
		return eventDispatcher.on(eventClass);
	}

	@Override
	public void close()
			throws IOException {
		clientsSelector.close();
	}

	private void listenProtocol(Protocol protocol) {
		protocol.outgoingTransfers()
				.map(transfer -> setProtocolHeader(transfer, protocol.getName()))
				.map(this::transferToClientPack)
				.subscribe(clientsSelector::send);
	}

	private Transfer setProtocolHeader(Transfer transfer, String protocolName) {
		return new ChannelTransfer(transfer.getHeaders().compose().header(Headers.PROTOCOL, protocolName).build(),
				transfer.channel(), transfer.getContentLength());
	}

	private ClientPack transferToClientPack(Transfer transfer) {
		Number destinationClientId = transfer.getHeaders().getNonNullNumber(Headers.DESTINATION_CLIENT_ID);

		byte[] headers;
		try {
			headers = JSON_MAPPER.writeValueAsBytes(transfer.getHeaders());
		}
		catch (JsonProcessingException e) {
			throw new IllegalStateException("Cannot write JSON", e);
		}

		ReadableByteChannel channel = transfer.channel();
		return new ClientPack(destinationClientId.longValue(), new Pack(headers, channel, transfer.getContentLength()));
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
		Headers headers = transfer.getHeaders();

		Number destinationClientId = headers.getNumber(Headers.DESTINATION_CLIENT_ID);
		if (destinationClientId != null) { // for any client, so we are redirecting without any processing
			clientsSelector.send(transferToClientPack(transfer));
		}
		else {
			Boolean isProtocolErrorResponse = headers.getBoolean(Headers.IS_PROTOCOL_ERROR_RESPONSE);
			if (isProtocolErrorResponse != null && isProtocolErrorResponse) {
				processErrorTransfer(transfer);
			}
			else {
				wrapWithErrorResponse(headers, () -> processSuccessTransfer(transfer));
			}
		}
	}

	private void processSuccessTransfer(Transfer transfer) {
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

	private void processErrorTransfer(Transfer transfer) {
		byte[] content = Try.supplyOrRethrow(transfer::readAll);
		throw new SonderRemoteException(new String(content));
	}

	private void wrapWithErrorResponse(Headers requestHeaders, Runnable runnable) {
		long clientId = requestHeaders.getNonNullNumber(Headers.SOURCE_CLIENT_ID).longValue();
		try {
			runnable.run();
		}
		catch (Exception e) {
			e.printStackTrace();

			Headers headers = Headers.builder()
					.header(Headers.IS_PROTOCOL_ERROR_RESPONSE, true)
					.header(Headers.DESTINATION_CLIENT_ID, clientId)
					.build();

			ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
			e.printStackTrace(new PrintStream(byteStream));
			byte[] content = byteStream.toByteArray();

			Transfer transfer = new StaticTransfer(headers, content);
			ClientPack clientPack = transferToClientPack(transfer);
			clientsSelector.send(clientPack);
		}
	}
}