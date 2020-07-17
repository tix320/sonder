package com.github.tix320.sonder.RPC;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

import com.github.tix320.sonder.api.client.SonderClient;
import com.github.tix320.sonder.api.server.SonderServer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Tigran Sargsyan on 14-Jul-20.
 */
public class ResponseTest {

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

		AtomicBoolean valueReturned = new AtomicBoolean(false);
		AtomicBoolean errorReturned = new AtomicBoolean(false);

		rpcService.getAnyValue().subscribe(response -> {
			if (response.isSuccess()) {
				valueReturned.set(true);
			}
		});

		Thread.sleep(1000);

		assertTrue(valueReturned.get());

		// -------------------------------

		valueReturned.set(false);
		errorReturned.set(false);

		rpcService.throwAnyException().subscribe(response -> {
			if (response.isSuccess()) {
				valueReturned.set(true);
			}
			else {
				errorReturned.set(true);
			}
		});

		Thread.sleep(1000);

		assertFalse(valueReturned.get());
		assertTrue(errorReturned.get());

		// -------------------------------

		valueReturned.set(false);
		errorReturned.set(false);

		rpcService.throwAnyExceptionWithoutHandle();

		Thread.sleep(1000);

		sonderClient.close();
		sonderServer.close();
	}
}
