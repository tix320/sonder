package com.gitlab.tixtix320.sonder.client;

import com.gitlab.tixtix320.sonder.api.common.rpc.Endpoint;
import com.gitlab.tixtix320.sonder.api.common.rpc.extra.ClientID;

/**
 * @author Tigran.Sargsyan on 08-Apr-19
 */
@Endpoint("chat")
public class B {

	@Endpoint("message")
	public void foo(String message, @ClientID Long dfad) {
		System.out.println(message);
	}
}
