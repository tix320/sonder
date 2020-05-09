package com.github.tix320.sonder.api.client;

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
import com.github.tix320.sonder.api.client.event.SonderClientEvent;
import com.github.tix320.sonder.api.common.communication.*;
import com.github.tix320.sonder.api.common.topic.Topic;
import com.github.tix320.sonder.internal.client.ServerConnection;
import com.github.tix320.sonder.internal.client.topic.ClientTopicProtocol;
import com.github.tix320.sonder.internal.common.BuiltInProtocol;
import com.github.tix320.sonder.internal.common.communication.Pack;
import com.github.tix320.sonder.internal.common.rpc.protocol.RPCProtocol;
import com.github.tix320.sonder.internal.event.SonderEventDispatcher;

/**
 * Entry point class for your socket client.
 * Provides main functionality for communicating with server.
 *
 * Communication is performed by sending and receiving transfer objects {@link Transfer}.
 * Each transfer is handled by some protocol {@link Protocol}, which will be selected by header of transfer {@link Headers#PROTOCOL}.
 *
 * You can register any protocol by calling method {@link #registerProtocol(Protocol)}.
 * There are some built-in protocols {@link BuiltInProtocol}, which names is reserved and cannot be used.
 *
 * Create client builder by calling method {@link #forAddress}.
 *
 * @author tix32 on 20-Dec-18
 * @see Protocol
 * @see Transfer
 */
public final class SonderClient implements Closeable {

	private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

	private final Map<String, Protocol> protocols;

	private final ServerConnection connection;

	private final SonderEventDispatcher<SonderClientEvent> eventDispatcher;

	private final CopyOnWriteArrayList<Subscription> subscriptions;

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

	SonderClient(ServerConnection connection, Map<String, Protocol> protocols,
				 SonderEventDispatcher<SonderClientEvent> eventDispatcher) {
		this.connection = connection;
		this.protocols = new ConcurrentHashMap<>(protocols);
		this.eventDispatcher = eventDispatcher;
		this.subscriptions = new CopyOnWriteArrayList<>();
	}

	public synchronized void connect() throws IOException {
		reset();

		connection.connect();

		List<Subscription> subscriptionList = new ArrayList<>();

		connection.incomingRequests()
				.map(this::convertDataPackToTransfer)
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
	 * Register topic for protocol {@link ClientTopicProtocol}
	 *
	 * @param topic      name
	 * @param dataType   which will be used while transferring data in topic
	 * @param bufferSize for buffering last received data
	 * @param <T>        type of topic data
	 *
	 * @return topic {@link Topic}
	 *
	 * @throws IllegalStateException if {@link ClientTopicProtocol} not registered
	 */
	public <T> Topic<T> registerTopic(String topic, TypeReference<T> dataType, int bufferSize) {
		Protocol protocol = protocols.get(BuiltInProtocol.TOPIC.getName());
		if (protocol == null) {
			throw new IllegalStateException("Topic protocol not registered");
		}
		return ((ClientTopicProtocol) protocol).registerTopic(topic, dataType, bufferSize);
	}

	/**
	 * * Register topic for protocol {@link ClientTopicProtocol}
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

	public <T extends SonderClientEvent> Observable<T> onEvent(Class<T> eventClass) {
		return eventDispatcher.on(eventClass);
	}

	@Override
	public void close() throws IOException {
		connection.close();
	}

	private void reset() {
		subscriptions.forEach(Subscription::unsubscribe);
		subscriptions.clear();
	}

	private Subscription listenProtocol(Protocol protocol) {
		AtomicReference<Subscription> subscriptionHolder = new AtomicReference<>();
		protocol.outgoingTransfers()
				.map(transfer -> setProtocolHeader(transfer, protocol.getName()))
				.map(this::transferToDataPack)
				.subscribe(Subscriber.<Pack>builder().onSubscribe(subscriptionHolder::set)
						.onPublish(connection::send)
						.build());

		return subscriptionHolder.get();
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
