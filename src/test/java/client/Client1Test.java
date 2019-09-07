package client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;

import io.titix.sonder.client.Client;

/**
 * @author Tigran.Sargsyan on 24-Jan-19
 */
public final class Client1Test {

	public static void main(String[] args) throws IOException, InterruptedException {
		Client client = Client.run("localhost", 8888, List.of("client"), List.of("client"));

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
