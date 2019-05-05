package server;


import io.titix.sonder.Endpoint;

/**
 * @author tix32 on 19-Feb-19
 */
@Endpoint("sd")
public class TestEndpoint {

	@Endpoint("")
	public Object foo(String list) {
		return 34;
	}
}
