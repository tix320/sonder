package com.github.tix320.sonder.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.tix320.sonder.api.client.Clonder;
import com.github.tix320.sonder.api.common.topic.Topic;

/**
 * @author Tigran.Sargsyan on 24-Jan-19
 */
public final class Topic1Test {

	public static void main(String[] args)
			throws IOException, InterruptedException {
		Clonder clonder = Clonder.forAddress(new InetSocketAddress("localhost", 8888)).withTopicProtocol().build();

		clonder.getRPCService(A.class).send("lmfaoo").subscribe(integers -> System.out.println(integers));
		Topic<List<String>> topic = clonder.registerTopic("foo", new TypeReference<>() {});
		topic.asObservable().subscribe(list -> System.out.println((list.get(0) + 3)));
		BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(System.in));
		while (true) {
			String message = bufferedReader.readLine();
			long start = System.currentTimeMillis();
			topic.publish(List.of(message)).subscribe(none -> System.out.println(System.currentTimeMillis() - start));
		}
	}


}
