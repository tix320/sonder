package com.github.tix320.sonder.RPC;

import java.io.IOException;
import java.net.InetSocketAddress;

import com.github.tix320.sonder.api.client.SonderClient;
import com.github.tix320.sonder.api.client.rpc.ClientRPCProtocol;
import com.github.tix320.sonder.api.server.SonderServer;
import com.github.tix320.sonder.api.server.rpc.ServerRPCProtocol;
import org.junit.jupiter.api.AfterEach;

/**
 * @author Tigran Sargsyan on 25-Aug-20
 */
public abstract class BaseTest {

	private static final String HOST = "localhost";
	private static final int PORT = 33335;

	private SonderServer sonderServer;

	private SonderClient sonderClient;

	protected ClientRPCProtocol rpcProtocol;

	public void setUp() throws IOException {
		ServerRPCProtocol rpcProtocol = ServerRPCProtocol.builder()
				.registerEndpointClasses(ServerEndpoint.class)
				.build();

		sonderServer = SonderServer.forAddress(new InetSocketAddress(PORT)).registerProtocol(rpcProtocol).build();

		sonderServer.start();

		this.rpcProtocol = ClientRPCProtocol.builder().registerOriginInterfaces(ClientService.class).build();

		sonderClient = SonderClient.forAddress(new InetSocketAddress(HOST, PORT))
				.registerProtocol(this.rpcProtocol)
				.build();

		sonderClient.start();
	}

	@AfterEach
	public void close() throws IOException {
		sonderClient.stop();
		sonderServer.stop();
	}
}
