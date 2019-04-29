package client;

import io.titix.sonder.Endpoint;

/**
 * @author Tigran.Sargsyan on 08-Apr-19
 */
@Endpoint("chat")
public class B {

	@Endpoint("message")
	public void foo(String message) {
		System.out.println(message);
	}
}
