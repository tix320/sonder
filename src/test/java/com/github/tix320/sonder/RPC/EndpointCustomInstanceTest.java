package com.github.tix320.sonder.RPC;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.tix320.sonder.api.client.SonderClient;
import com.github.tix320.sonder.api.client.communication.ClientSideProtocol;
import com.github.tix320.sonder.api.common.RPCProtocolBuilder.BuildResult;
import com.github.tix320.sonder.api.common.rpc.build.OriginInstanceResolver;
import com.github.tix320.sonder.api.server.SonderServer;
import com.github.tix320.sonder.api.server.communication.ServerSideProtocol;
import com.github.tix320.sonder.internal.common.rpc.protocol.RPCProtocol;
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

		ServerSideProtocol rpcProtocol = RPCProtocol.forServer()
				.registerEndpointInstances(List.of(serverEndpoint))
				.build()
				.getProtocol();

		sonderServer = SonderServer.forAddress(new InetSocketAddress(PORT)).registerProtocol(rpcProtocol).build();

		sonderServer.start();

		BuildResult<ClientSideProtocol> buildResult = RPCProtocol.forClient()
				.registerOriginInterfaces(ClientService.class)
				.build();

		ClientSideProtocol protocol = buildResult.getProtocol();
		OriginInstanceResolver originInstanceResolver = buildResult.getOriginInstanceResolver();

		sonderClient = SonderClient.forAddress(new InetSocketAddress(HOST, PORT)).registerProtocol(protocol).build();

		sonderClient.connect();

		AtomicInteger holder = new AtomicInteger();
		originInstanceResolver.get(ClientService.class).testFactory().subscribe(holder::set);

		Thread.sleep(5000);

		assertEquals(5, holder.get());

		sonderClient.close();
		sonderServer.close();
	}
}
