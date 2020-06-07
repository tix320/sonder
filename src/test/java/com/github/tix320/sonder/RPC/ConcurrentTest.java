package com.github.tix320.sonder.RPC;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import com.github.tix320.sonder.api.client.SonderClient;
import com.github.tix320.sonder.api.server.SonderServer;
import org.junit.jupiter.api.Test;

import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author Tigran Sargsyan on 06-Jun-20.
 */
public class ConcurrentTest {

	private static final String HOST = "localhost";
	private static final int PORT = 33335;

	@Test
	public void test() throws IOException, InterruptedException {
		SonderServer sonderServer = SonderServer.forAddress(new InetSocketAddress(PORT))
				.withRPCProtocol(builder -> builder.scanClasses(ServerEndpoint.class))
				.build();
		sonderServer.start();

		int usersCount = 20;

		Set<Integer> responses = Collections.newSetFromMap(new ConcurrentHashMap<>());

		List<SonderClient> clients = new ArrayList<>();

		for (int i = 1; i <= usersCount; i++) {
			SonderClient sonderClient = SonderClient.forAddress(new InetSocketAddress(HOST, PORT))
					.withRPCProtocol(builder -> builder.scanClasses(ClientService.class))
					.contentTimeoutDurationFactory(contentLength -> Duration.ofSeconds(100))
					.build();

			sonderClient.connect();

			clients.add(sonderClient);

			sonderClient.getRPCService(ClientService.class).getStringLength("f".repeat(i)).subscribe(responses::add);
		}

		Thread.sleep(2000);

		Set<Integer> expectedResponses = IntStream.range(1, usersCount + 1).boxed().collect(toSet());
		assertEquals(expectedResponses, responses);

		for (SonderClient client : clients) {
			client.close();
		}
		sonderServer.close();
	}
}
