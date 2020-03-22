package com.github.tix320.sonder.internal.server.topic;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tix320.kiwi.api.check.Try;
import com.github.tix320.kiwi.api.reactive.observable.MonoObservable;
import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.kiwi.api.reactive.publisher.Publisher;
import com.github.tix320.kiwi.api.util.None;
import com.github.tix320.sonder.api.common.communication.*;
import com.github.tix320.sonder.api.common.topic.Topic;
import com.github.tix320.sonder.internal.common.BuiltInProtocol;
import com.github.tix320.sonder.internal.common.topic.TopicAction;
import com.github.tix320.sonder.internal.common.util.Threads;

/**
 * Topic protocol implementation, which stores topics for clients communicating without using server directly.
 * Each incoming transfer is handled for 3 type actions, {publish, subscribe, unsubscribe}.
 *
 * {publish} is used for publishing any data to topic, which will be receive each client, who subscribed in topic.
 *
 * {subscribe} is used for subscribing to topic for receiving data, published by other clients in that topic.
 * Also subscribed client may publish your data after subscribe.
 *
 * {unsubscribe} is used for unsubscribing from topic, finishing receiving data from this topic.
 *
 * For subscribing to topic and then publishing data you must have register this topic {@link #registerTopic(String, TypeReference, int)}  } and then subscribe {@link Topic#asObservable()}.
 * For publishing data you can use {@link Topic} interface, which instance you will be receive after topic register.
 *
 * Pay attention, that the server also can subscribe to topic.
 */
public class ServerTopicProtocol implements Protocol {

	private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

	private final Map<String, Queue<Long>> topics;

	private final Map<String, Publisher<Object>> topicPublishers;

	private final Map<String, TypeReference<?>> dataTypesByTopic;

	private final Publisher<Transfer> outgoingRequests;

	public ServerTopicProtocol() {
		this.topics = new ConcurrentHashMap<>();
		this.topicPublishers = new ConcurrentHashMap<>();
		this.dataTypesByTopic = new ConcurrentHashMap<>();
		this.outgoingRequests = Publisher.simple();
	}

	@Override
	public void handleIncomingTransfer(Transfer transfer)
			throws IOException {
		Headers headers = transfer.getHeaders();

		String topic = headers.getNonNullString(Headers.TOPIC);

		TopicAction action = TopicAction.valueOf(headers.getNonNullString(Headers.TOPIC_ACTION));

		Number sourceClientId = headers.getNonNullNumber(Headers.SOURCE_ID);

		Queue<Long> clients = topics.computeIfAbsent(topic, key -> new ConcurrentLinkedQueue<>());

		switch (action) {
			case PUBLISH:
				Transfer staticTransfer = new StaticTransfer(headers, transfer.readAll());
				handleForServer(staticTransfer);
				publishToClients(topic, staticTransfer, clients, sourceClientId.longValue());
				break;
			case SUBSCRIBE:
				transfer.readAllInVain();
				clients.add(sourceClientId.longValue());
				break;
			case UNSUBSCRIBE:
				transfer.readAllInVain();
				clients.remove(sourceClientId.longValue());
				break;
			default:
				throw new IllegalStateException(String.format("Invalid topic action %s", action));
		}
	}

	@Override
	public Observable<Transfer> outgoingTransfers() {
		return outgoingRequests.asObservable();
	}

	@Override
	public String getName() {
		return BuiltInProtocol.TOPIC.getName();
	}

	@Override
	public void close() {
		outgoingRequests.complete();
	}

	/**
	 * * Register topic
	 *
	 * @param topic      name
	 * @param dataType   which will be used while transferring data in topic
	 * @param bufferSize for buffering last received data
	 *
	 * @return topic {@link Topic}
	 */
	public <T> Topic<T> registerTopic(String topic, TypeReference<T> dataType, int bufferSize) {
		if (topicPublishers.containsKey(topic)) {
			throw new IllegalArgumentException(String.format("Topic %s already registered", topic));
		}
		topicPublishers.put(topic, bufferSize > 0 ? Publisher.buffered(bufferSize) : Publisher.simple());
		dataTypesByTopic.put(topic, dataType);
		return new TopicImpl<>(topic);
	}

	private void handleForServer(Transfer transfer)
			throws IOException {
		Headers headers = transfer.getHeaders();
		byte[] content = transfer.readAll();

		String topic = (String) headers.get(Headers.TOPIC);

		Publisher<Object> publisher = topicPublishers.get(topic);
		if (publisher == null) {
			return;
		}

		TypeReference<?> dataType = dataTypesByTopic.get(topic);
		Object contentObject = Try.supplyOrRethrow(() -> JSON_MAPPER.readValue(content, dataType));
		Threads.runAsync(() -> {
			try {
				publisher.publish(contentObject);
			}
			catch (IllegalArgumentException e) {
				throw new IllegalStateException(
						String.format("Registered topic's (%s) data type (%s) is not compatible with received JSON %s",
								topic, dataType.getType().getTypeName(), contentObject), e);
			}
		});
	}

	private void publishToClients(String topic, Transfer transfer, Collection<Long> clients, long sourceClientId) {
		for (Long clientId : clients) {
			if (clientId != sourceClientId) {
				Headers newHeaders = Headers.builder()
						.header(Headers.SOURCE_ID, sourceClientId)
						.header(Headers.DESTINATION_ID, clientId)
						.header(Headers.TOPIC, topic)
						.header(Headers.TOPIC_ACTION, TopicAction.RECEIVE_ITEM)
						.build();
				outgoingRequests.publish(
						new ChannelTransfer(newHeaders, transfer.channel(), transfer.getContentLength()));
			}
		}
		Headers newHeaders = Headers.builder()
				.header(Headers.DESTINATION_ID, sourceClientId)
				.header(Headers.TOPIC, topic)
				.header(Headers.TOPIC_ACTION, TopicAction.PUBLISH_RESPONSE)
				.header(Headers.RESPONSE_KEY, transfer.getHeaders().get(Headers.RESPONSE_KEY))
				.build();
		outgoingRequests.publish(new StaticTransfer(newHeaders, new byte[0]));
	}

	private final class TopicImpl<T> implements Topic<T> {

		private final String topic;

		private TopicImpl(String topic) {
			this.topic = topic;
		}

		@Override
		public MonoObservable<None> publish(Object data) {
			Queue<Long> clients = topics.computeIfAbsent(topic, key -> new ConcurrentLinkedQueue<>());
			for (Long clientId : clients) {
				Headers newHeaders = Headers.builder()
						.header(Headers.DESTINATION_ID, clientId)
						.header(Headers.TOPIC, topic)
						.header(Headers.TOPIC_ACTION, TopicAction.RECEIVE_ITEM)
						.build();
				outgoingRequests.publish(
						new StaticTransfer(newHeaders, Try.supplyOrRethrow(() -> JSON_MAPPER.writeValueAsBytes(data))));
			}
			return Observable.of(None.SELF);
		}

		@Override
		public Observable<T> asObservable() {
			@SuppressWarnings("unchecked")
			Observable<T> typed = (Observable<T>) topicPublishers.get(topic).asObservable();
			return typed;
		}

		@Override
		public String getName() {
			return topic;
		}

		@Override
		public void removeSubscriber(long clientId) {
			topics.get(topic).remove(clientId);
		}
	}
}
