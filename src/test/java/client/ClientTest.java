package client;

import java.util.List;

import io.titix.sonder.Client;

/**
 * @author Tigran.Sargsyan on 24-Jan-19
 */
public final class ClientTest {

	public static void main(String[] args) throws InterruptedException {
		Client client = new Client("localhost", 777);

		A service = client.getService(A.class);

		service.foo(List.of()).subscribe(System.out::println);
		System.out.println("lol");
		Thread.sleep(2000000000000000L);
	}
}
