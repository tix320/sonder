package client;

import io.titix.sonder.Endpoint;

@Endpoint("super")
public class ServerEndpoint {

	@Endpoint("")
	public Integer foo(String message) {
		System.out.println(message);
		return message.length();
	}
}
