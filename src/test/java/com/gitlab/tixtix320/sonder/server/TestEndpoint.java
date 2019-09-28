package com.gitlab.tixtix320.sonder.server;


import com.gitlab.tixtix320.sonder.api.common.Endpoint;

/**
 * @author tix32 on 19-Feb-19
 */
@Endpoint("ujex")
public class TestEndpoint {

	@Endpoint("wtf")
	public Object foo(String message, String message2) {
		System.out.println(message);
		return message.length();
	}
}
