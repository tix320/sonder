package com.gitlab.tixtix320.sonder.internal.client.topic;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitlab.tixtix320.kiwi.api.check.Try;
import com.gitlab.tixtix320.kiwi.api.observable.Observable;
import com.gitlab.tixtix320.kiwi.api.observable.subject.Subject;
import com.gitlab.tixtix320.kiwi.api.util.IDGenerator;
import com.gitlab.tixtix320.kiwi.api.util.None;
import com.gitlab.tixtix320.sonder.api.common.communication.Headers;
import com.gitlab.tixtix320.sonder.api.common.communication.Protocol;
import com.gitlab.tixtix320.sonder.api.common.communication.StaticTransfer;
import com.gitlab.tixtix320.sonder.api.common.communication.Transfer;
import com.gitlab.tixtix320.sonder.api.common.topic.Topic;
import com.gitlab.tixtix320.sonder.internal.common.communication.BuiltInProtocol;

public class ClientTopicProtocol implements Protocol {

	private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

	private final Map<String, TypeReference<?>> dataTypesByTopic;

	private final Map<String, Subject<Object>> topicSubjects;

	private final Map<Long, Subject<None>> responseSubjects;

	private final IDGenerator transferIdGenerator;

	private final Map<String, None> subscriptions; // this is used as Set

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
	public void handleIncomingTransfer(Transfer transfer)
			throws IOException {
		Headers headers = transfer.getHeaders();

		byte[] content = transfer.readAll();

		String topic = headers.getNonNullString(Headers.TOPIC);

		Boolean isInvoke = headers.getBoolean(Headers.IS_INVOKE);
		if (isInvoke != null && isInvoke) {
			Subject<Object> subject = topicSubjects.get(topic);
			if (subject == null) {
				throw new IllegalStateException(String.format("Topic %s not found", topic));
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
		else { // is response
			Number transferKey = headers.getNonNullNumber(Headers.TRANSFER_KEY);
			Subject<None> subject = responseSubjects.get(transferKey.longValue());
			if (subject == null) {
				throw new IllegalStateException("Invalid transfer key");
			}
			subject.next(None.SELF);
			subject.complete();
		}
	}

	@Override
	public Observable<Transfer> outgoingTransfers() {
		return requests.asObservable();
	}

	@Override
	public String getName() {
		return BuiltInProtocol.TOPIC.getName();
	}

	@Override
	public void close() {
		requests.complete();
		topicSubjects.values().forEach(Subject::complete);
		responseSubjects.values().forEach(Subject::complete);
	}

	public <T> Topic<T> registerTopic(String topic, TypeReference<T> dataType, int bufferSize) {
		if (topicSubjects.containsKey(topic)) {
			throw new IllegalArgumentException(String.format("Publisher for topic %s already registered", topic));
		}
		topicSubjects.put(topic, bufferSize > 0 ? Subject.buffered(bufferSize) : Subject.single());
		dataTypesByTopic.put(topic, dataType);
		return new TopicImpl<>(topic);
	}

	private final class TopicImpl<T> implements Topic<T> {

		private final String topic;

		private TopicImpl(String topic) {
			this.topic = topic;
		}

		@Override
		public Observable<None> publish(Object data) {
			long transferKey = transferIdGenerator.next();
			Headers headers = Headers.builder()
					.header(Headers.TOPIC, topic)
					.header(Headers.TOPIC_ACTION, "publish")
					.header(Headers.TRANSFER_KEY, transferKey)
					.header(Headers.IS_INVOKE, true)
					.build();
			Subject<None> subject = Subject.single();
			responseSubjects.put(transferKey, subject);
			requests.next(new StaticTransfer(headers, Try.supplyOrRethrow(() -> JSON_MAPPER.writeValueAsBytes(data))));
			return subject.asObservable();
		}

		@Override
		public Observable<T> asObservable() {
			subscriptions.computeIfAbsent(topic, key -> {
				Headers headers = Headers.builder()
						.header(Headers.TOPIC, topic)
						.header(Headers.TOPIC_ACTION, "subscribe")
						.build();
				requests.next(new StaticTransfer(headers, new byte[0]));
				return None.SELF;
			});

			@SuppressWarnings("unchecked")
			Observable<T> typed = (Observable<T>) topicSubjects.get(topic).asObservable();
			return typed;
		}

		@Override
		public String getName() {
			return topic;
		}
	}
}
