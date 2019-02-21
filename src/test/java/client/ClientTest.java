package client;

import java.util.ArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.titix.sonder.Client;

/**
 * @author Tigran.Sargsyan on 24-Jan-19
 */
public class ClientTest {

	public static void main(String[] args) throws InterruptedException {
		Client client = new Client("localhost", 777);

		A service = client.getService(A.class);

		service.foo(Stream.generate(() -> new ArrayList()).limit(100000).collect(Collectors.toList()))
				.thenAccept(integer -> System.out.println(integer));
		System.out.println("lol");
		Thread.sleep(2000000000000000L);
	}
}
