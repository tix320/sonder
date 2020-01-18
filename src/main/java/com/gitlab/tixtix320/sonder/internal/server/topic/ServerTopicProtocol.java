package com.gitlab.tixtix320.sonder.internal.server.topic;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitlab.tixtix320.kiwi.api.check.Try;
import com.gitlab.tixtix320.kiwi.api.observable.Observable;
import com.gitlab.tixtix320.kiwi.api.observable.subject.Subject;
import com.gitlab.tixtix320.kiwi.api.util.None;
import com.gitlab.tixtix320.sonder.api.common.communication.*;
import com.gitlab.tixtix320.sonder.api.common.topic.Topic;

public class ServerTopicProtocol implements Protocol {

	private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

	private final Map<String, Queue<Long>> topics;

	private final Map<String, Subject<Object>> topicSubjects;

	private final Map<String, TypeReference<?>> dataTypesByTopic;

	private final Subject<Transfer> outgoingRequests;

	public ServerTopicProtocol() {
		this.topics = new ConcurrentHashMap<>();
		this.topicSubjects = new ConcurrentHashMap<>();
		this.dataTypesByTopic = new ConcurrentHashMap<>();
		this.outgoingRequests = Subject.single();
	}

	@Override
	public void handleIncomingTransfer(Transfer transfer)
			throws IOException {
		Headers headers = transfer.getHeaders();

		String topic = headers.getNonNullString(Headers.TOPIC);

		String action = headers.getNonNullString(Headers.TOPIC_ACTION);

		Number sourceClientId = headers.getNonNullNumber(Headers.SOURCE_CLIENT_ID);

		Queue<Long> clients = topics.computeIfAbsent(topic, key -> new ConcurrentLinkedQueue<>());

		switch (action) {
			case "publish":
				Transfer staticTransfer = new StaticTransfer(headers, transfer.readAll());
				handleForServer(staticTransfer);
				publishToClients(topic, staticTransfer, clients, sourceClientId.longValue());
				break;
			case "subscribe":
				transfer.readAllInVain();
				clients.add(sourceClientId.longValue());
				break;
			case "unsubscribe":
				transfer.readAllInVain();
				clients.remove(sourceClientId.longValue());
				break;
			default:
				throw new IllegalStateException(String.format("Invalid topic action %s", action));
		}
	}

	public void handleForServer(Transfer transfer)
			throws IOException {
		Headers headers = transfer.getHeaders();
		byte[] content = transfer.readAll();

		String topic = (String) headers.get(Headers.TOPIC);

		Subject<Object> subject = topicSubjects.get(topic);
		if (subject == null) {
			return;
		}

		TypeReference<?> dataType = dataTypesByTopic.get(topic);
		Object contentObject = Try.supplyOrRethrow(() -> JSON_MAPPER.readValue(content, dataType));
		try {
			subject.next(contentObject);
		}
		catch (IllegalArgumentException e) {
			throw new IllegalStateException(
					String.format("Registered topic's (%s) data type (%s) is not compatible with received JSON %s",
							topic, dataType.getType().getTypeName(), contentObject), e);
		}
	}

	@Override
	public Observable<Transfer> outgoingTransfers() {
		return outgoingRequests.asObservable();
	}

	@Override
	public String getName() {
		return "sonder-topic";
	}

	@Override
	public void close() {
		outgoingRequests.complete();
	}

	public <T> Topic<T> registerTopic(String topic, TypeReference<T> dataType, int bufferSize) {
		if (topicSubjects.containsKey(topic)) {
			throw new IllegalArgumentException(String.format("Publisher for topic %s already registered", topic));
		}
		topicSubjects.put(topic, bufferSize > 0 ? Subject.buffered(bufferSize) : Subject.single());
		dataTypesByTopic.put(topic, dataType);
		return new TopicImpl<>(topic);
	}

	private void publishToClients(String topic, Transfer transfer, Collection<Long> clients, long sourceClientId) {
		for (Long clientId : clients) {
			if (clientId != sourceClientId) {
				Headers newHeaders = Headers.builder()
						.header(Headers.SOURCE_CLIENT_ID, sourceClientId)
						.header(Headers.DESTINATION_CLIENT_ID, clientId)
						.header(Headers.TOPIC, topic)
						.header(Headers.IS_INVOKE, true)
						.build();
				outgoingRequests.next(new ChannelTransfer(newHeaders, transfer.channel(), transfer.getContentLength()));
			}
		}
		Headers newHeaders = Headers.builder()
				.header(Headers.DESTINATION_CLIENT_ID, sourceClientId)
				.header(Headers.TOPIC, topic)
				.header(Headers.TRANSFER_KEY, transfer.getHeaders().get(Headers.TRANSFER_KEY))
				.build();
		outgoingRequests.next(new StaticTransfer(newHeaders, new byte[0]));
	}

	private final class TopicImpl<T> implements Topic<T> {

		private final String topic;

		private TopicImpl(String topic) {
			this.topic = topic;
		}

		@Override
		public Observable<None> publish(Object data) {
			Queue<Long> clients = topics.computeIfAbsent(topic, key -> new ConcurrentLinkedQueue<>());
			for (Long clientId : clients) {
				Headers newHeaders = Headers.builder()
						.header(Headers.DESTINATION_CLIENT_ID, clientId)
						.header(Headers.TOPIC, topic)
						.header(Headers.IS_INVOKE, true)
						.build();
				outgoingRequests.next(
						new StaticTransfer(newHeaders, Try.supplyOrRethrow(() -> JSON_MAPPER.writeValueAsBytes(data))));
			}
			return Observable.of(None.SELF);
		}

		@Override
		public Observable<T> asObservable() {
			@SuppressWarnings("unchecked")
			Observable<T> typed = (Observable<T>) topicSubjects.computeIfAbsent(topic, key -> Subject.single())
					.asObservable();
			return typed;
		}

		@Override
		public String getName() {
			return topic;
		}
	}
}
