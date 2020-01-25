package com.github.tix320.sonder.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.tix320.sonder.api.common.topic.Topic;
import com.github.tix320.sonder.api.server.Sonder;

/**
 * @author Tigran.Sargsyan on 24-Jan-19
 */
public final class ServerTest {

	public static void main(String[] args)
			throws InterruptedException, IOException {
		Sonder sonder = Sonder.forAddress(new InetSocketAddress(Integer.parseInt(args[0])))
				.withRPCProtocol("com.github.tix320.sonder.server")
				.withTopicProtocol()
				.build();

		Topic<List<String>> topic = sonder.registerTopic("foo", new TypeReference<>() {});
		topic.asObservable().subscribe(list -> System.out.println((list.get(0) + 3)));
		BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(System.in));
		while (true) {
			String message = bufferedReader.readLine();
			long start = System.currentTimeMillis();
			topic.publish(List.of(message)).subscribe(none -> System.out.println(System.currentTimeMillis() - start));
		}
	}
}
