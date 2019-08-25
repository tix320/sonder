package client;

import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.titix.sonder.client.Client;

/**
 * @author Tigran.Sargsyan on 24-Jan-19
 */
public final class Client1Test {

	public static void main(String[] args) throws IOException, InterruptedException {
		Client client = Client.run("localhost", 8888, List.of("client"), List.of("client"));
		//Client client3 = Client.run("localhost", 8888, List.of("client"), List.of("client"));

		A service = client.getService(A.class);

		BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(System.in));
		while (true) {
			String message = bufferedReader.readLine();
			long start = System.currentTimeMillis();
			service.send(message)
					.subscribe(object -> System.out.println(System.currentTimeMillis() - start));
		}

	}


}
