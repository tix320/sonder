package server;

import java.util.List;

import io.titix.sonder.Server;

/**
 * @author Tigran.Sargsyan on 24-Jan-19
 */
public final class ServerTest {

	public static void main(String[] args) throws InterruptedException {
		Server server = Server.run(777, List.of("server"), List.of("server"));


		Thread.sleep(2000000000000000L);
		Runnable service = server.getService(Runnable.class);
		service.run();

		server.stop();
	}
}
