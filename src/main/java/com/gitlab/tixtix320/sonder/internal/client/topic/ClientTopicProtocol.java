package com.gitlab.tixtix320.sonder.internal.client.topic;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.gitlab.tixtix320.kiwi.api.observable.Observable;
import com.gitlab.tixtix320.kiwi.api.observable.subject.Subject;
import com.gitlab.tixtix320.kiwi.api.util.IDGenerator;
import com.gitlab.tixtix320.sonder.api.common.communication.Headers;
import com.gitlab.tixtix320.sonder.api.common.communication.Protocol;
import com.gitlab.tixtix320.sonder.api.common.communication.Transfer;
import com.gitlab.tixtix320.sonder.api.common.topic.TopicPublisher;
import com.gitlab.tixtix320.sonder.internal.common.communication.InvalidHeaderException;

public class ClientTopicProtocol implements Protocol {

	private static final Object NONE = new Object();

	private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

	private final Map<String, TypeReference<?>> dataTypesByTopic;

	private final Map<String, Subject<Object>> topicSubjects;

	private final Map<Long, Subject<Void>> responseSubjects;

	private final IDGenerator transferIdGenerator;

	private final Map<String, Object> subscriptions; // this is used via Set

	private final Subject<Transfer> requests;

	public ClientTopicProtocol() {
		this.dataTypesByTopic = new ConcurrentHashMap<>();
		this.topicSubjects = new ConcurrentHashMap<>();
		this.transferIdGenerator = new IDGenerator();
		this.responseSubjects = new ConcurrentHashMap<>();
		this.subscriptions = new ConcurrentHashMap<>();
		this.requests = Subject.single();
	}

	@Override
	public void handleTransfer(Transfer transfer) {
		Headers headers = transfer.getHeaders();
		JsonNode contentNode = transfer.getContent();

		Object topic = headers.get(Headers.TOPIC);
		if (!(topic instanceof String)) {
			throw new InvalidHeaderException(Headers.TOPIC, topic, String.class);
		}

		Object isResponse = headers.get(Headers.IS_RESPONSE);
		if (isResponse == null || (isResponse instanceof Boolean && !((boolean) isResponse))) {
			Subject<Object> subject = topicSubjects.get(topic);
			if (subject == null) {
				throw new IllegalStateException(String.format("Topic %s not found", topic));
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
		else { // is response
			Object transferKey = headers.get(Headers.TRANSFER_KEY);
			if (!(transferKey instanceof Number)) {
				throw new InvalidHeaderException(Headers.TRANSFER_KEY, transferKey, Number.class);
			}
			Subject<Void> subject = responseSubjects.get(((Number) transferKey).longValue());
			if (subject == null) {
				throw new IllegalStateException("Invalid transfer key");
			}
			subject.next((Void) null);
			subject.complete();
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
		topicSubjects.values().forEach(Subject::complete);
		responseSubjects.values().forEach(Subject::complete);
	}

	public <T> TopicPublisher<T> registerTopicPublisher(String topic, TypeReference<T> dataType) {
		if (topicSubjects.containsKey(topic)) {
			throw new IllegalArgumentException(String.format("Publisher for topic %s already registered", topic));
		}
		topicSubjects.put(topic, Subject.single());
		dataTypesByTopic.put(topic, dataType);
		return new TopicPublisherImpl<>(topic);
	}

	private final class TopicPublisherImpl<T> implements TopicPublisher<T> {

		private final String topic;

		private TopicPublisherImpl(String topic) {
			this.topic = topic;
		}

		@Override
		public Observable<Void> publish(Object data) {
			long transferKey = transferIdGenerator.next();
			Headers headers = Headers.builder()
					.header(Headers.TOPIC, topic)
					.header(Headers.TOPIC_ACTION, "publish")
					.header(Headers.TRANSFER_KEY, transferKey)
					.build();
			Subject<Void> subject = Subject.single();
			responseSubjects.put(transferKey, subject);
			requests.next(new Transfer(headers, JSON_MAPPER.valueToTree(data)));
			return subject.asObservable();
		}

		@Override
		public Observable<T> asObservable() {
			subscriptions.computeIfAbsent(topic, key -> {
				Headers headers = Headers.builder()
						.header(Headers.TOPIC, topic)
						.header(Headers.TOPIC_ACTION, "subscribe")
						.build();
				requests.next(new Transfer(headers, new TextNode("")));
				return NONE;
			});

			@SuppressWarnings("unchecked")
			Observable<T> typed = (Observable<T>) topicSubjects.get(topic).asObservable();
			return typed;
		}

		@Override
		public String getTopic() {
			return topic;
		}
	}
}
