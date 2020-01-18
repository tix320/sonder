package com.github.tix320.sonder.client;

import com.github.tix320.sonder.api.common.rpc.Endpoint;

@Endpoint("super")
public class ServerEndpoint {

	@Endpoint("")
	public Integer foo(String message) {
		System.out.println(message);
		return message.length();
	}
}
