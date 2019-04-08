package client;

import io.titix.sonder.Endpoint;

/**
 * @author Tigran.Sargsyan on 08-Apr-19
 */
@Endpoint("foo")
public class B {

	@Endpoint("lol")
	public void foo(String message) {
		System.out.println(message);
	}
}
