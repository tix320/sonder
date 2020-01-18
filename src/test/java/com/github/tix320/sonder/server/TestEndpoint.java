package com.github.tix320.sonder.server;


import java.util.List;

import com.github.tix320.sonder.api.common.rpc.Endpoint;

/**
 * @author tix32 on 19-Feb-19
 */
@Endpoint("chat")
public class TestEndpoint {

	@Endpoint("message")
	public List<Integer> foo(String message) {
		System.out.println(message);
		return List.of(5, 6);
	}
}
