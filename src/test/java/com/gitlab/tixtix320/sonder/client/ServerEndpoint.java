package com.gitlab.tixtix320.sonder.client;

import com.gitlab.tixtix320.sonder.api.common.rpc.Endpoint;

@Endpoint("super")
public class ServerEndpoint {

	@Endpoint("")
	public Integer foo(String message) {
		System.out.println(message);
		return message.length();
	}
}
