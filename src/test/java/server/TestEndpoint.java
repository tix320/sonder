package server;


import java.util.List;

import io.titix.sonder.Endpoint;

/**
 * @author tix32 on 19-Feb-19
 */
@Endpoint("super")
public class TestEndpoint {

	@Endpoint("")
	public Object foo(String message) {
		System.out.println(message);
		return message.length();
	}
}
