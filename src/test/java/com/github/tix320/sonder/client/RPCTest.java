package com.github.tix320.sonder.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;

import com.github.tix320.sonder.api.client.Clonder;

public class RPCTest {

	public static void main(String[] args)
			throws IOException, InterruptedException {
		Clonder clonder = Clonder.forAddress(new InetSocketAddress("localhost", 8888))
				.withRPCProtocol("com.github.tix320.sonder.client")
				.build();

		A a = clonder.getRPCService(A.class);
		BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(System.in));
		while (true) {
			String message = bufferedReader.readLine();
			long start = System.currentTimeMillis();
			a.send(message).subscribe(integers -> System.out.println(System.currentTimeMillis() - start));
		}
	}
}
