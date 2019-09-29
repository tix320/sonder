package com.gitlab.tixtix320.sonder.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;

import com.gitlab.tixtix320.sonder.api.client.Clonder;

public class Client2Test {

	public static void main(String[] args) throws IOException {
		Clonder clonder = Clonder.run("localhost", 8888, List.of("com.gitlab.tixtix320.sonder.client"),
				List.of("com.gitlab.tixtix320.sonder.client"));

		A service = clonder.getService(A.class);

		BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(System.in));
		while (true) {
			String message = bufferedReader.readLine();
			long start = System.currentTimeMillis();
			service.send(1, message);
			//.subscribe(object -> System.out.println(System.currentTimeMillis() - start));
		}

	}
}
