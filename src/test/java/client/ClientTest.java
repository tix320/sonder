package client;

import io.titix.sonder.Client;

/**
 * @author Tigran.Sargsyan on 24-Jan-19
 */
public class ClientTest {

	public static void main(String[] args) throws InterruptedException {
		Client client = new Client("localhost",777);


		Thread.sleep(2000000000000000L);
	}
}
