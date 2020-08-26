package com.github.tix320.sonder.RPC;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.github.tix320.kiwi.api.reactive.observable.Subscriber;
import com.github.tix320.kiwi.api.reactive.observable.Subscription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class RPCObservableTest extends BaseTest {

	@Override
	@BeforeEach
	public void setUp() throws IOException {
		super.setUp();
	}

	@Test
	public void test() throws InterruptedException, IOException {
		ClientService rpcService = rpcProtocol.getOrigin(ClientService.class);

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
