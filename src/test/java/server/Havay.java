package server;

import java.util.List;

import io.titix.sonder.Endpoint;

/**
 * @author tix32 on 19-Feb-19
 */
@Endpoint("foo")
public class Havay {

	@Endpoint("lol")
	public int foo(List<?> wdsd) {
		System.out.println(wdsd.size());
		return 34;
	}
}
