package com.gitlab.tixtix320.sonder.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.gitlab.tixtix320.sonder.api.client.Clonder;
import com.gitlab.tixtix320.sonder.api.common.topic.Topic;

public class Topic2Test {

	public static void main(String[] args)
			throws IOException {
		Clonder clonder = Clonder.forAddress(new InetSocketAddress("localhost", 8888)).withTopicProtocol().build();

		Topic<List<String>> topic = clonder.registerTopic("foo", new TypeReference<>() {});
		topic.asObservable().subscribe(System.out::println);
		BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(System.in));
		while (true) {
			String message = bufferedReader.readLine();
			long start = System.currentTimeMillis();
			topic.publish(List.of(message)).subscribe(none -> System.out.println(System.currentTimeMillis() - start));
		}
	}
}
