package server;

import java.util.List;

import io.titix.sonder.server.Server;

/**
 * @author Tigran.Sargsyan on 24-Jan-19
 */
public final class ServerTest {

	public static void main(String[] args)
			throws InterruptedException {
		Server server = Server.run(Integer.parseInt(args[0]), List.of("server"), List.of("server"));

		TestOrigin service = server.getService(TestOrigin.class);

		Thread.sleep(500000);
		//service.foo("hello", -9223372036854775808L).subscribe(object -> System.out.println(object));
	}
}
