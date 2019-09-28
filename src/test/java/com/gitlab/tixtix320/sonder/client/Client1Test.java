package com.gitlab.tixtix320.sonder.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;

import com.gitlab.tixtix320.sonder.api.client.Client;

/**
 * @author Tigran.Sargsyan on 24-Jan-19
 */
public final class Client1Test {

	public static void main(String[] args) throws IOException, InterruptedException {
		Client client = Client.run("localhost", 8888, List.of("com/gitlab/tixtix320/sonder/client"), List.of(
				"com/gitlab/tixtix320/sonder/client"));

		A service = client.getService(A.class);

		BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(System.in));
		while (true) {
			String message = bufferedReader.readLine();
			long start = System.currentTimeMillis();
			service.send(2, message);
			//.subscribe(object -> System.out.println(System.currentTimeMillis() - start));
		}

	}


}
