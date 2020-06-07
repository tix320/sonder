package com.github.tix320.sonder.RPC;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.tix320.sonder.api.client.SonderClient;
import com.github.tix320.sonder.api.server.SonderServer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author Tigran Sargsyan on 03-Jun-20.
 */
public class BinaryTest {

	private static final String HOST = "localhost";
	private static final int PORT = 33335;

	public static SonderServer sonderServer;
	public static SonderClient sonderClient;

	@Test
	public void test() throws InterruptedException, IOException {
		sonderServer = SonderServer.forAddress(new InetSocketAddress(PORT))
				.withRPCProtocol(builder -> builder.scanClasses(ServerEndpoint.class))
				.build();

		sonderClient = SonderClient.forAddress(new InetSocketAddress(HOST, PORT))
				.withRPCProtocol(builder -> builder.scanClasses(ClientService.class))
				.build();

		sonderServer.start();
		sonderClient.connect();

		ClientService rpcService = sonderClient.getRPCService(ClientService.class);

		AtomicInteger responseHolder = new AtomicInteger();

		rpcService.putByes(new byte[]{10, 20, 30}).subscribe(responseHolder::set);
		Thread.sleep(300);
		assertEquals(60, responseHolder.get());

		rpcService.putByes(new byte[]{50, 60, 70}).subscribe(responseHolder::set);
		Thread.sleep(300);
		assertEquals(180, responseHolder.get());

		rpcService.putByes(new byte[]{23, 12, 32}).subscribe(responseHolder::set);
		Thread.sleep(300);
		assertEquals(67, responseHolder.get());


		sonderClient.close();
		sonderServer.close();
	}
}
