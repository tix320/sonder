package com.github.tix320.sonder.RPC;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.github.tix320.kiwi.api.reactive.observable.Subscriber;
import com.github.tix320.kiwi.api.reactive.observable.Subscription;
import com.github.tix320.sonder.api.client.SonderClient;
import com.github.tix320.sonder.api.server.SonderServer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class RPCObservableTest {

	private static final String HOST = "localhost";
	private static final int PORT = 33335;

	public static SonderServer sonderServer;
	public static SonderClient sonderClient;

	@Test
	public void test()
			throws InterruptedException {
		sonderServer = SonderServer.forAddress(new InetSocketAddress(PORT))
				.withRPCProtocol(builder -> builder.scanClasses(ServerEndpoint.class))
				.build();

		sonderClient = SonderClient.forAddress(new InetSocketAddress(HOST, PORT))
				.withRPCProtocol(builder -> builder.scanClasses(ClientService.class))
				.build();

		ClientService rpcService = sonderClient.getRPCService(ClientService.class);

		List<Integer> list = new ArrayList<>();
		AtomicReference<Subscription> subscriptionHolder = new AtomicReference<>();
		rpcService.numbers()
				.subscribe(Subscriber.<Integer>builder().onSubscribe(subscriptionHolder::set).onPublish(list::add));

		Thread.sleep(300);
		ServerEndpoint.publisher.publish(4);
		Thread.sleep(300);
		assertEquals(List.of(4), list);
		ServerEndpoint.publisher.publish(5);
		Thread.sleep(300);
		assertEquals(List.of(4, 5), list);
		subscriptionHolder.get().unsubscribe();
		Thread.sleep(500);
		ServerEndpoint.publisher.publish(6);
		assertEquals(List.of(4, 5), list);


	}
}
