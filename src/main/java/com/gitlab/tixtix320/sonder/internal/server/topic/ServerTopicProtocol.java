package com.gitlab.tixtix320.sonder.internal.server.topic;

import java.io.IOException;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.gitlab.tixtix320.kiwi.api.observable.Observable;
import com.gitlab.tixtix320.kiwi.api.observable.subject.Subject;
import com.gitlab.tixtix320.sonder.api.common.communication.Headers;
import com.gitlab.tixtix320.sonder.api.common.communication.Protocol;
import com.gitlab.tixtix320.sonder.api.common.communication.Transfer;
import com.gitlab.tixtix320.sonder.internal.common.communication.InvalidHeaderException;

public class ServerTopicProtocol implements Protocol {

	private final Map<String, Queue<Long>> topics;

	private final Subject<Transfer> requests;

	public ServerTopicProtocol() {
		this.topics = new ConcurrentHashMap<>();
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
			for (Long clientId : clients) {
				if (sourceClientId != clientId) {
					Headers newHeaders = Headers.builder()
							.header(Headers.SOURCE_CLIENT_ID, sourceClientId)
							.header(Headers.DESTINATION_CLIENT_ID, clientId)
							.header(Headers.TOPIC, topic)
							.build();
					requests.next(new Transfer(newHeaders, contentNode));
				}
			}
			Headers newHeaders = Headers.builder()
					.header(Headers.DESTINATION_CLIENT_ID, sourceClientId)
					.header(Headers.TOPIC, topic)
					.header(Headers.IS_RESPONSE, true)
					.header(Headers.TRANSFER_KEY, headers.get(Headers.TRANSFER_KEY))
					.build();
			requests.next(new Transfer(newHeaders, new TextNode("")));
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
}
