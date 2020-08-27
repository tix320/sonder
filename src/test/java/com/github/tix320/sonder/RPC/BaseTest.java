package com.github.tix320.sonder.RPC;

import java.io.IOException;
import java.net.InetSocketAddress;

import com.github.tix320.sonder.api.client.SonderClient;
import com.github.tix320.sonder.api.server.SonderServer;
import com.github.tix320.sonder.api.common.rpc.RPCProtocol;
import org.junit.jupiter.api.AfterEach;

/**
 * @author Tigran Sargsyan on 25-Aug-20
 */
public abstract class BaseTest {

	private static final String HOST = "localhost";
	private static final int PORT = 33335;

	private SonderServer sonderServer;

	private SonderClient sonderClient;

	protected RPCProtocol rpcProtocol;

	public void setUp() throws IOException {
		RPCProtocol rpcProtocol = RPCProtocol.forServer().registerEndpointClasses(ServerEndpoint.class).build();

		sonderServer = SonderServer.forAddress(new InetSocketAddress(PORT)).registerProtocol(rpcProtocol).build();

		sonderServer.start();

		this.rpcProtocol = RPCProtocol.forClient().registerOriginInterfaces(ClientService.class).build();

		sonderClient = SonderClient.forAddress(new InetSocketAddress(HOST, PORT))
				.registerProtocol(this.rpcProtocol)
				.build();

		sonderClient.connect();
	}

	@AfterEach
	public void close() throws IOException {
		sonderClient.close();
		sonderServer.close();
	}
}
