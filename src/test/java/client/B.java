package client;

import io.titix.sonder.Endpoint;
import io.titix.sonder.extra.ClientID;

/**
 * @author Tigran.Sargsyan on 08-Apr-19
 */
@Endpoint("chat")
public class B {

	@Endpoint("message")
	public String foo(String message, @ClientID Long dfad) {
		System.out.println(message);
		return message;
	}
}
