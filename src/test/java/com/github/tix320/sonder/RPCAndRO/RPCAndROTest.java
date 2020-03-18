package com.github.tix320.sonder.RPCAndRO;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.github.tix320.sonder.api.client.SonderClient;
import com.github.tix320.sonder.api.server.SonderServer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RPCAndROTest {

	private static final String HOST = "localhost";
	private static final int PORT = 33333;

	public static SonderServer sonderServer;
	public static SonderClient sonderClient;

	@Test
	void test()
			throws InterruptedException, IOException {
		sonderServer = SonderServer.forAddress(new InetSocketAddress(PORT))
				.withRPCProtocol(builder -> builder.scanClasses(ServerEndpoint.class))
				.withRemoteObjectProtocol(List.of(RemoteObjectInterface.class))
				.build();

		sonderClient = SonderClient.forAddress(new InetSocketAddress(HOST, PORT))
				.withRPCProtocol(builder -> builder.scanClasses(ClientService.class))
				.withRemoteObjectProtocol(List.of(RemoteObjectInterface.class))
				.build();

		// ServerRemoteObjectProtocol protocol = sonderServer.getProtocol(BuiltInProtocol.RO.getName());
		// long objectId = protocol.registerObject(new RemoteObjectImpl());

		ClientService rpcService = sonderClient.getRPCService(ClientService.class);
		AtomicBoolean called = new AtomicBoolean(false);
		rpcService.getRemoteObjectId().subscribe(id -> {
			RemoteObjectInterface remoteObject = sonderClient.getRemoteObject(RemoteObjectInterface.class, id);
			String answer = remoteObject.foo("foo");
			System.out.println(answer);
			assertEquals("foo".repeat(2), answer);
			called.set(true);
		});
		Thread.sleep(2000);
		assertTrue(called.get());
	}


}
