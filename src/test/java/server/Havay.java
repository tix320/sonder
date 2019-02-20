package server;

import io.titix.sonder.Endpoint;

/**
 * @author tix32 on 19-Feb-19
 */
@Endpoint("foo")
public class Havay {

	@Endpoint("lol")
	public int foo() {

		return 34;
	}
}
