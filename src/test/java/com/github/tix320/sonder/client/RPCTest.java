package com.github.tix320.sonder.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.util.function.Consumer;

import com.github.tix320.kiwi.api.proxy.AnnotationInterceptor;
import com.github.tix320.sonder.api.client.SonderClient;
import com.github.tix320.sonder.api.client.RPCProtocolBuilder;
import com.github.tix320.sonder.server.MyAnno;
import com.github.tix320.sonder.server.MyInterceptor;

public class RPCTest {

	public static void main(String[] args)
			throws IOException, InterruptedException {
		Consumer<RPCProtocolBuilder> rpcProtocolBuilder = builder -> {
			builder.scanPackages("com.github.tix320.sonder.client")
					.registerInterceptor(new AnnotationInterceptor<>(MyAnno.class, new MyInterceptor()));
		};

		SonderClient sonderClient = SonderClient.forAddress(new InetSocketAddress("localhost", 8888))
				.withRPCProtocol(rpcProtocolBuilder)
				.build();

		A a = sonderClient.getRPCService(A.class);
		BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(System.in));
		while (true) {
			String message = bufferedReader.readLine();
			long start = System.currentTimeMillis();
			a.send(message).subscribe(integers -> System.out.println(integers));
		}
	}
}
