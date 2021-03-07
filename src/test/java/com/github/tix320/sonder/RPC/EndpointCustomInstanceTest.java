package com.github.tix320.sonder.RPC;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.tix320.sonder.api.client.SonderClient;
import com.github.tix320.sonder.api.client.rpc.ClientRPCProtocol;
import com.github.tix320.sonder.api.server.SonderServer;
import com.github.tix320.sonder.api.server.rpc.ServerRPCProtocol;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class EndpointCustomInstanceTest {

	private static final String HOST = "localhost";
	private static final int PORT = 33335;

	public static SonderServer sonderServer;

	public static SonderClient sonderClient;

	@Test
	public void test() throws InterruptedException, IOException {
		ServerEndpoint serverEndpoint = new ServerEndpoint();
		serverEndpoint.forTest = 5;

		ServerRPCProtocol rpcProtocol = ServerRPCProtocol.builder()
				.registerEndpointInstances(List.of(serverEndpoint))
				.build();

		sonderServer = SonderServer.forAddress(new InetSocketAddress(PORT)).registerProtocol(rpcProtocol).build();

		sonderServer.start();

		ClientRPCProtocol protocol = ClientRPCProtocol.builder().registerOriginInterfaces(ClientService.class).build();

		sonderClient = SonderClient.forAddress(new InetSocketAddress(HOST, PORT)).registerProtocol(protocol).build();

		sonderClient.start();

		AtomicInteger holder = new AtomicInteger();
		protocol.getOrigin(ClientService.class).testFactory().subscribe(holder::set);

		Thread.sleep(5000);

		assertEquals(5, holder.get());

		sonderClient.stop();
		sonderServer.stop();
	}
}
