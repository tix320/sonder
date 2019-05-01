package client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;

import io.titix.sonder.client.Client;

public class Client2Test {

	public static void main(String[] args) throws IOException {
		Client client = Client.run("18.194.51.33", 8888, List.of("client"), List.of("client"));

		A service = client.getService(A.class);

		BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(System.in));
		while (true) {
			String message = bufferedReader.readLine();
			service.send(message, -9223372036854775808L);
		}

	}
}