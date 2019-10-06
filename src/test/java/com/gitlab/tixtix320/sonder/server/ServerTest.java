package com.gitlab.tixtix320.sonder.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.gitlab.tixtix320.sonder.api.common.topic.TopicPublisher;
import com.gitlab.tixtix320.sonder.api.server.Sonder;

/**
 * @author Tigran.Sargsyan on 24-Jan-19
 */
public final class ServerTest {

	public static void main(String[] args) throws InterruptedException, IOException {
		Sonder sonder = Sonder.withBuiltInProtocols(Integer.parseInt(args[0]), "com.gitlab.tixtix320.sonder.server");

		TopicPublisher<List<String>> topicPublisher = sonder.registerTopicPublisher("foo", new TypeReference<>() {});
		topicPublisher.asObservable().subscribe(list -> System.out.println((list.get(0) + 3)));
		BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(System.in));
		while (true) {
			String message = bufferedReader.readLine();
			long start = System.currentTimeMillis();
			topicPublisher.publish(List.of(message))
					.subscribe(none -> System.out.println(System.currentTimeMillis() - start));
		}
	}
}
