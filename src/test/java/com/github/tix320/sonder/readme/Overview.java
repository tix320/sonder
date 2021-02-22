package com.github.tix320.sonder.readme;

import java.io.IOException;
import java.net.InetSocketAddress;

import com.github.tix320.kiwi.api.reactive.observable.MonoObservable;
import com.github.tix320.sonder.api.client.SonderClient;
import com.github.tix320.sonder.api.client.rpc.ClientRPCProtocol;
import com.github.tix320.sonder.api.common.rpc.Endpoint;
import com.github.tix320.sonder.api.common.rpc.Origin;
import com.github.tix320.sonder.api.server.SonderServer;
import com.github.tix320.sonder.api.server.rpc.ServerRPCProtocol;

@Endpoint("someService")
class ServerEndpoint {

	@Endpoint("someMethod")
	public Integer getLengthOfString(String s) {
		return s.length();
	}
}

class ServerTest {

	public static void main(String[] args) throws IOException {
		// Creating protocol for communication
		ServerRPCProtocol rpcProtocol = SonderServer.getRPCProtocolBuilder()
				.registerEndpointClasses(ServerEndpoint.class)
				.build();

		// Creating the server itself and registering the protocol
		SonderServer sonderServer = SonderServer.forAddress(new InetSocketAddress(8888))
				.registerProtocol(rpcProtocol)
				.build();

		sonderServer.events()
				.newConnections()
				.subscribe(client -> System.out.printf("Client %s connected", client.getId()));

		sonderServer.events()
				.deadConnections()
				.subscribe(client -> System.out.printf("Client %s disconnected", client.getId()));

		// start the server
		sonderServer.start();
	}
}

@Origin("someService")
interface ClientOrigin {

	@Origin("someMethod")
	MonoObservable<Integer> getLengthOfString(String s);
}

class ClientTest {
	public static void main(String[] args) throws IOException {
		// Creating protocol for communication
		ClientRPCProtocol rpcProtocol = SonderClient.getRPCProtocolBuilder()
				.registerOriginInterfaces(ClientOrigin.class)
				.build();

		// Creating the client itself and registering the protocol
		SonderClient sonderClient = SonderClient.forAddress(new InetSocketAddress("localhost", 8888))
				.registerProtocol(rpcProtocol)
				.build();

		sonderClient.events().connectionState().subscribe(state -> {
			switch (state) {
				case IDLE:
					System.out.println("Initial state or disconnected");
				case CONNECTED:
					System.out.println("Connected to server");
					break;
				case CLOSED:
					System.out.println("Connection closed by user");
					break;
			}
		});

		// connect to server
		sonderClient.start();

		// Get RPC interface for some request
		ClientOrigin clientOrigin = rpcProtocol.getOrigin(ClientOrigin.class);

		// Call RPC method and subscribe to response
		clientOrigin.getLengthOfString("my_first_rpc_call").subscribe(length -> {
			System.out.println(length);
			assert length == 17;
		});
	}
}
