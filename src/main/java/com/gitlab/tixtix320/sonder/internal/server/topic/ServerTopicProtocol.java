package com.gitlab.tixtix320.sonder.internal.server.topic;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.gitlab.tixtix320.kiwi.api.observable.Observable;
import com.gitlab.tixtix320.kiwi.api.observable.subject.Subject;
import com.gitlab.tixtix320.kiwi.api.util.None;
import com.gitlab.tixtix320.sonder.api.common.communication.Headers;
import com.gitlab.tixtix320.sonder.api.common.communication.Protocol;
import com.gitlab.tixtix320.sonder.api.common.communication.Transfer;
import com.gitlab.tixtix320.sonder.api.common.topic.TopicPublisher;
import com.gitlab.tixtix320.sonder.internal.common.communication.InvalidHeaderException;

public class ServerTopicProtocol implements Protocol {

	private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

	private final Map<String, Queue<Long>> topics;

	private final Map<String, Subject<Object>> topicSubjects;

	private final Map<String, TypeReference<?>> dataTypesByTopic;

	private final Subject<Transfer> requests;

	public ServerTopicProtocol() {
		this.topics = new ConcurrentHashMap<>();
		this.topicSubjects = new ConcurrentHashMap<>();
		this.dataTypesByTopic = new ConcurrentHashMap<>();
		this.requests = Subject.single();
	}

	@Override
	public void handleTransfer(Transfer transfer) {
		Headers headers = transfer.getHeaders();

		Object topic = headers.get(Headers.TOPIC);
		if (!(topic instanceof String)) {
			throw new InvalidHeaderException(Headers.TOPIC, topic, String.class);
		}

		Object action = headers.get(Headers.TOPIC_ACTION);
		if (!(action instanceof String)) {
			throw new InvalidHeaderException(Headers.TOPIC_ACTION, action, String.class);
		}

		Object sourceClientId = headers.get(Headers.SOURCE_CLIENT_ID);
		if (!(sourceClientId instanceof Number)) {
			throw new InvalidHeaderException(Headers.SOURCE_CLIENT_ID, sourceClientId, Number.class);
		}

		Queue<Long> clients = topics.computeIfAbsent((String) topic, key -> new ConcurrentLinkedQueue<>());
		if (action.equals("publish")) {
			handleForServer(transfer);
			publishToClients((String) topic, transfer, clients, ((Number) sourceClientId).longValue());

		}
		else if (action.equals("subscribe")) {
			clients.add(((Number) sourceClientId).longValue());
		}
		else if (action.equals("unsubscribe")) {
			clients.remove(((Number) sourceClientId).longValue());
		}
		else {
			throw new IllegalStateException(String.format("Invalid topic action %s", action));
		}
	}

	public void handleForServer(Transfer transfer) {
		Headers headers = transfer.getHeaders();
		JsonNode contentNode = transfer.getContent();

		String topic = (String) headers.get(Headers.TOPIC);

		Subject<Object> subject = topicSubjects.get(topic);
		if (subject == null) {
			return;
		}

		TypeReference<?> dataType = dataTypesByTopic.get(topic);
		try {
			Object object = JSON_MAPPER.convertValue(contentNode, dataType);
			subject.next(object);
		}
		catch (IllegalArgumentException e) {
			throw new IllegalStateException(
					String.format("Registered topic's (%s) data type (%s) is not compatible with received JSON %s",
							topic, dataType.getType().getTypeName(), contentNode), e);
		}
	}

	@Override
	public Observable<Transfer> transfers() {
		return requests.asObservable();
	}

	@Override
	public String getName() {
		return "sonder-topic";
	}

	@Override
	public void close() throws IOException {
		requests.complete();
	}

	public <T> TopicPublisher<T> registerTopicPublisher(String topic, TypeReference<T> dataType) {
		if (topicSubjects.containsKey(topic)) {
			throw new IllegalArgumentException(String.format("Publisher for topic %s already registered", topic));
		}
		topicSubjects.put(topic, Subject.single());
		dataTypesByTopic.put(topic, dataType);
		return new TopicPublisherImpl<>(topic);
	}

	private void publishToClients(String topic, Transfer transfer, Collection<Long> clients, long sourceClientId) {
		for (Long clientId : clients) {
			if (sourceClientId != clientId) {
				Headers newHeaders = Headers.builder()
						.header(Headers.SOURCE_CLIENT_ID, sourceClientId)
						.header(Headers.DESTINATION_CLIENT_ID, clientId)
						.header(Headers.TOPIC, topic)
						.build();
				requests.next(new Transfer(newHeaders, transfer.getContent()));
			}
		}
		Headers newHeaders = Headers.builder()
				.header(Headers.DESTINATION_CLIENT_ID, sourceClientId)
				.header(Headers.TOPIC, topic)
				.header(Headers.IS_RESPONSE, true)
				.header(Headers.TRANSFER_KEY, transfer.getHeaders().get(Headers.TRANSFER_KEY))
				.build();
		requests.next(new Transfer(newHeaders, new TextNode("")));
	}

	private final class TopicPublisherImpl<T> implements TopicPublisher<T> {

		private final String topic;

		private TopicPublisherImpl(String topic) {
			this.topic = topic;
		}

		@Override
		public Observable<None> publish(Object data) {
			Queue<Long> clients = topics.computeIfAbsent(topic, key -> new ConcurrentLinkedQueue<>());
			for (Long clientId : clients) {
				Headers newHeaders = Headers.builder()
						.header(Headers.DESTINATION_CLIENT_ID, clientId)
						.header(Headers.TOPIC, topic)
						.build();
				requests.next(new Transfer(newHeaders, JSON_MAPPER.valueToTree(data)));
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
		public String getTopic() {
			return topic;
		}
	}
}
