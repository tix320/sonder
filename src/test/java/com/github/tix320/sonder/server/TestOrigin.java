package com.github.tix320.sonder.server;

import com.github.tix320.sonder.api.common.rpc.Origin;
import com.github.tix320.sonder.api.common.rpc.extra.ClientID;
import com.github.tix320.kiwi.api.observable.Observable;

/**
 * @author Tigran.Sargsyan on 30-Jan-19
 */
@Origin("super")
public interface TestOrigin {

	@Origin("")
	Observable<Integer> foo(String message, @ClientID long id);
}
