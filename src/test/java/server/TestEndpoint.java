package server;

import java.util.List;

import io.titix.sonder.Endpoint;

/**
 * @author tix32 on 19-Feb-19
 */
@Endpoint("foo")
public class TestEndpoint {

	@Endpoint("lol")
	public int foo(List<?> list) {
		System.out.println(list.size());
		return 34;
	}
}
