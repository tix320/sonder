package com.github.tix320.sonder.RPC;

import java.io.IOException;
import java.net.InetSocketAddress;

import com.github.tix320.sonder.api.client.SonderClient;
import com.github.tix320.sonder.api.client.communication.ClientSideProtocol;
import com.github.tix320.sonder.api.common.RPCProtocolBuilder.BuildResult;
import com.github.tix320.sonder.api.common.rpc.build.OriginInstanceResolver;
import com.github.tix320.sonder.api.server.SonderServer;
import com.github.tix320.sonder.api.server.communication.ServerSideProtocol;
import com.github.tix320.sonder.internal.common.rpc.protocol.RPCProtocol;
import org.junit.jupiter.api.AfterEach;

/**
 * @author Tigran Sargsyan on 25-Aug-20
 */
public abstract class BaseTest {

	private static final String HOST = "localhost";
	private static final int PORT = 33335;

	private SonderServer sonderServer;

	private SonderClient sonderClient;

	protected OriginInstanceResolver originInstanceResolver;

	public void setUp() throws IOException {
		ServerSideProtocol rpcProtocol = RPCProtocol.forServer()
				.registerEndpointClasses(ServerEndpoint.class)
				.build()
				.getProtocol();

		sonderServer = SonderServer.forAddress(new InetSocketAddress(PORT)).registerProtocol(rpcProtocol).build();

		sonderServer.start();

		BuildResult<ClientSideProtocol> buildResult = RPCProtocol.forClient()
				.registerOriginInterfaces(ClientService.class)
				.build();

		ClientSideProtocol protocol = buildResult.getProtocol();
		originInstanceResolver = buildResult.getOriginInstanceResolver();

		sonderClient = SonderClient.forAddress(new InetSocketAddress(HOST, PORT)).registerProtocol(protocol).build();

		sonderClient.connect();
	}

	@AfterEach
	public void close() throws IOException {
		sonderClient.close();
		sonderServer.close();
	}
}
