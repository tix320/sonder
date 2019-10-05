package com.gitlab.tixtix320.sonder.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.gitlab.tixtix320.sonder.api.client.Clonder;
import com.gitlab.tixtix320.sonder.api.common.topic.TopicPublisher;

/**
 * @author Tigran.Sargsyan on 24-Jan-19
 */
public final class Client1Test {

	public static void main(String[] args) throws IOException, InterruptedException {
		Clonder clonder = Clonder.withBuiltInProtocols("localhost", 8888, "com.gitlab.tixtix320.sonder.client");

		TopicPublisher<List<String>> topicPublisher = clonder.registerTopicPublisher("foo", new TypeReference<>() {});
		topicPublisher.asObservable().subscribe(list -> System.out.println((list.get(0) + 3)));
		BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(System.in));
		while (true) {
			String message = bufferedReader.readLine();
			long start = System.currentTimeMillis();
			topicPublisher.publish(List.of(message, message + "pizdec"))
					.subscribe(aVoid -> System.out.println(System.currentTimeMillis() - start));
		}
	}


}
