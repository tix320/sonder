package com.github.tix320.sonder.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.tix320.sonder.api.common.RPCProtocolBuilder;
import com.github.tix320.sonder.api.common.topic.Topic;
import com.github.tix320.sonder.api.server.SonderServer;

/**
 * @author Tigran.Sargsyan on 24-Jan-19
 */
public final class ServerTest {

	public static void main(String[] args)
			throws IOException {
		Consumer<RPCProtocolBuilder> rpcProtocolBuilder = builder -> builder.scanPackages(
				"com.github.tix320.sonder.server").registerInterceptor(new MyInterceptor());

		SonderServer sonderServer = SonderServer.forAddress(new InetSocketAddress(Integer.parseInt(args[0])))
				.withRPCProtocol(rpcProtocolBuilder)
				.withTopicProtocol()
				.build();

		Topic<List<String>> topic = sonderServer.registerTopic("foo", new TypeReference<>() {});
		topic.asObservable().subscribe(list -> System.out.println((list.get(0) + 3)));
		BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(System.in));
		while (true) {
			String message = bufferedReader.readLine();
			long start = System.currentTimeMillis();
			topic.publish(List.of(message)).subscribe(none -> System.out.println(System.currentTimeMillis() - start));
		}
	}
}
