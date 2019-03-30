package server;

import io.titix.sonder.Server;

/**
 * @author Tigran.Sargsyan on 24-Jan-19
 */
public final class ServerTest {

	public static void main(String[] args) throws InterruptedException {
		new Server(777);

		Thread.sleep(2000000000000000L);
	}
}
