package client;

import java.util.List;

import io.titix.sonder.client.Client;

/**
 * @author Tigran.Sargsyan on 24-Jan-19
 */
public final class ClientTest {

	public static void main(String[] args) {
		Client client = Client.run("localhost", 777, List.of("client"), List.of("client"));

		A service = client.getService(A.class);

		service.foo("barev client", -9223372036854775808L).subscribe(System.out::println);
		System.out.println("lol");
	}
}
