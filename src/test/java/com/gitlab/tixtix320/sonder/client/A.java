package com.gitlab.tixtix320.sonder.client;

import com.gitlab.tixtix320.sonder.api.common.rpc.Origin;
import com.gitlab.tixtix320.sonder.api.common.rpc.extra.ClientID;

/**
 * @author Tigran.Sargsyan on 30-Jan-19
 */
@Origin("chat")
public interface A {

	@Origin("message")
	void send(@ClientID long id, String message);
}
