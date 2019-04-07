package server;

import java.util.List;

import io.titix.sonder.Endpoint;
import io.titix.sonder.extra.ClientID;

/**
 * @author tix32 on 19-Feb-19
 */
@Endpoint("foo")
public class TestEndpoint {

	@Endpoint("lol")
	public int foo(List<?> list, @ClientID long a) {
		System.out.println(list.size());
		return 34;
	}
}
