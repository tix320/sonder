package com.github.tix320.sonder.api.server;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.kiwi.api.reactive.observable.Subscriber;
import com.github.tix320.kiwi.api.reactive.observable.Subscription;
import com.github.tix320.sonder.api.common.communication.*;
import com.github.tix320.sonder.api.common.topic.Topic;
import com.github.tix320.sonder.api.server.event.SonderServerEvent;
import com.github.tix320.sonder.internal.common.BuiltInProtocol;
import com.github.tix320.sonder.internal.common.communication.Pack;
import com.github.tix320.sonder.internal.common.rpc.protocol.RPCProtocol;
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

	private final SonderEventDispatcher<SonderServerEvent> eventDispatcher;

	private final CopyOnWriteArrayList<Subscription> subscriptions;

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
				 SonderEventDispatcher<SonderServerEvent> eventDispatcher) {
		this.clientsSelector = clientsSelector;
		this.protocols = new ConcurrentHashMap<>(protocols);
		this.eventDispatcher = eventDispatcher;
		this.subscriptions = new CopyOnWriteArrayList<>();
	}

	public synchronized void start() throws IOException {
		reset();

		clientsSelector.run();

		List<Subscription> subscriptionList = new ArrayList<>();

		clientsSelector.incomingRequests()
				.map(this::clientPackToTransfer)
				.subscribe(Subscriber.<Transfer>builder().onSubscribe((Consumer<Subscription>) subscriptionList::add)
						.onPublish(this::processTransfer)
						.build());

		protocols.forEach((protocolName, protocol) -> subscriptionList.add(listenProtocol(protocol)));

		subscriptions.addAll(subscriptionList);
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

		subscriptions.add(listenProtocol(protocol));

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
	public void close() throws IOException {
		clientsSelector.close();
	}

	private void reset() {
		subscriptions.forEach(Subscription::unsubscribe);
		subscriptions.clear();
	}

	private Subscription listenProtocol(Protocol protocol) {
		AtomicReference<Subscription> subscriptionHolder = new AtomicReference<>();
		protocol.outgoingTransfers()
				.map(transfer -> setProtocolHeader(transfer, protocol.getName()))
				.map(this::transferToClientPack)
				.subscribe(Subscriber.<ClientPack>builder().onSubscribe(subscriptionHolder::set)
						.onPublish(clientsSelector::send)
						.build());

		return subscriptionHolder.get();
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
