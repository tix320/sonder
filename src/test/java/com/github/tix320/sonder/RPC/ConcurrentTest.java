package com.github.tix320.sonder.RPC;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import com.github.tix320.sonder.api.client.SonderClient;
import com.github.tix320.sonder.api.client.rpc.ClientRPCProtocol;
import com.github.tix320.sonder.api.server.SonderServer;
import com.github.tix320.sonder.api.server.rpc.ServerRPCProtocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author Tigran Sargsyan on 06-Jun-20.
 */
public class ConcurrentTest {

	private static final String HOST = "localhost";
	private static final int PORT = 33335;

	SonderServer sonderServer;

	@Test
	public void test() throws IOException, InterruptedException {
		ServerRPCProtocol rpcProtocol = ServerRPCProtocol.builder()
				.registerEndpointClasses(ServerEndpoint.class)
				.build();

		sonderServer = SonderServer.forAddress(new InetSocketAddress(PORT)).registerProtocol(rpcProtocol).build();

		sonderServer.start();

		int usersCount = 20;

		Set<Integer> responses = Collections.newSetFromMap(new ConcurrentHashMap<>());

		List<SonderClient> clients = new ArrayList<>();

		for (int i = 1; i <= usersCount; i++) {
			ClientRPCProtocol protocol = ClientRPCProtocol.builder()
					.registerOriginInterfaces(ClientService.class)
					.build();

			SonderClient sonderClient = SonderClient.forAddress(new InetSocketAddress(HOST, PORT))
					.registerProtocol(protocol)
					.build();

			sonderClient.start();

			clients.add(sonderClient);

			protocol.getOrigin(ClientService.class).getStringLength("f".repeat(i)).subscribe(responses::add);
		}

		Thread.sleep(5000);

		Set<Integer> expectedResponses = IntStream.range(1, usersCount + 1).boxed().collect(toSet());
		assertEquals(expectedResponses, responses);

		for (SonderClient client : clients) {
			client.stop();
		}
	}

	@AfterEach
	public void cleanup() throws IOException {
		sonderServer.stop();
	}
}
