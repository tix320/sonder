package com.github.tix320.sonder.topic;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.tix320.sonder.api.client.SonderClient;
import com.github.tix320.sonder.api.common.topic.Topic;
import com.github.tix320.sonder.api.server.SonderServer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Tigran Sargsyan on 14-May-20.
 */
public class TopicTest {

	private static final String HOST = "localhost";
	private static final int PORT = 33335;

	@Test
	public void test() throws InterruptedException, IOException {
		SonderServer sonderServer = SonderServer.forAddress(new InetSocketAddress(PORT)).withTopicProtocol().build();

		sonderServer.start();

		SonderClient sonderClient1 = SonderClient.forAddress(new InetSocketAddress(HOST, PORT))
				.withTopicProtocol()
				.build();

		sonderClient1.connect();

		SonderClient sonderClient2 = SonderClient.forAddress(new InetSocketAddress(HOST, PORT))
				.withTopicProtocol()
				.build();
		sonderClient2.connect();

		List<Integer> firstPublishItems = List.of(1, 2, 3);
		List<Integer> secondPublishItems = List.of(4, 5, 6);

		List<Integer> actualFirstList = new CopyOnWriteArrayList<>();
		List<Integer> actualSecondList = new CopyOnWriteArrayList<>();

		AtomicBoolean firstSent = new AtomicBoolean(false);
		AtomicBoolean secondSent = new AtomicBoolean(false);

		Topic<Integer> topic1 = sonderClient1.registerTopic("my-topic", new TypeReference<>() {});
		topic1.asObservable().subscribe(actualFirstList::add);
		firstPublishItems.forEach(integer -> topic1.publish(integer).subscribe(none -> firstSent.set(true)));

		Topic<Integer> topic2 = sonderClient2.registerTopic("my-topic", new TypeReference<>() {});
		topic2.asObservable().subscribe(actualSecondList::add);
		secondPublishItems.forEach(integer -> topic2.publish(integer).subscribe(none -> secondSent.set(true)));


		Thread.sleep(1000);


		assertEquals(3, actualFirstList.size());
		assertEquals(3, actualSecondList.size());


		for (int i = 1; i <= 3; i++) {
			assertTrue(actualSecondList.contains(i));
		}

		for (int i = 4; i <= 6; i++) {
			assertTrue(actualFirstList.contains(i));
		}
	}
}
