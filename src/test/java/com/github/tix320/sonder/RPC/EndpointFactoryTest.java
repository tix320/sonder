package com.github.tix320.sonder.RPC;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.tix320.sonder.api.client.SonderClient;
import com.github.tix320.sonder.api.server.SonderServer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class EndpointFactoryTest {

	private static final String HOST = "localhost";
	private static final int PORT = 33335;

	public static SonderServer sonderServer;

	public static SonderClient sonderClient;

	@Test
	public void test() throws InterruptedException, IOException {
		sonderServer = SonderServer.forAddress(new InetSocketAddress(PORT))
				.withRPCProtocol(builder -> builder.scanClasses(ServerEndpoint.class).endpointFactory(clazz -> {
					ServerEndpoint serverEndpoint = new ServerEndpoint();
					serverEndpoint.forTest = 5;
					return serverEndpoint;
				}))
				.build();

		sonderClient = SonderClient.forAddress(new InetSocketAddress(HOST, PORT))
				.withRPCProtocol(builder -> builder.scanClasses(ClientService.class))
				.build();

		sonderServer.start();
		sonderClient.connect();

		AtomicInteger holder = new AtomicInteger();
		sonderClient.getRPCService(ClientService.class).testFactory().subscribe(holder::set);

		Thread.sleep(5000);

		assertEquals(5, holder.get());

		sonderClient.close();
		sonderServer.close();
	}
}
