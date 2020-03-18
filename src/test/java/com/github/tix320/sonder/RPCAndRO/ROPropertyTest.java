package com.github.tix320.sonder.RPCAndRO;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import com.github.tix320.kiwi.api.reactive.property.Property;
import com.github.tix320.sonder.api.client.SonderClient;
import com.github.tix320.sonder.api.server.SonderServer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ROPropertyTest {

	private static final String HOST = "localhost";
	private static final int PORT = 33333;

	public static SonderServer sonderServer;
	public static SonderClient sonderClient;

	@Test
	void test()
			throws InterruptedException {
		sonderServer = SonderServer.forAddress(new InetSocketAddress(PORT))
				.withRemoteObjectProtocol(List.of(RemoteObjectInterface.class))
				.build();

		sonderClient = SonderClient.forAddress(new InetSocketAddress(HOST, PORT))
				.withRemoteObjectProtocol(List.of(RemoteObjectInterface.class))
				.build();

		RemoteObjectImpl serverObject = new RemoteObjectImpl();
		String identifier = "foo";
		sonderServer.registerObject(serverObject, identifier);

		RemoteObjectInterface clientRemoteObject = sonderClient.getRemoteObject(RemoteObjectInterface.class,
				identifier);
		Property<Integer> countProperty = clientRemoteObject.count();
		Thread.sleep(500);
		assertEquals(12, countProperty.get());
		serverObject.count().set(30);
		Thread.sleep(500);
		assertEquals(30, countProperty.get());

		List<String> list = new ArrayList<>();
		clientRemoteObject.texts().asObservable().subscribe(list::add);

		serverObject.texts().add("foo");
		Thread.sleep(100);
		assertEquals(List.of("foo"), list);
		serverObject.texts().addAll(List.of("boo", "doo"));
		Thread.sleep(100);
		assertEquals(List.of("foo", "boo", "doo"), list);
		assertEquals(List.of("foo", "boo", "doo"), serverObject.texts().list());
		assertEquals(List.of("foo", "boo", "doo"), clientRemoteObject.texts().list());

	}
}
