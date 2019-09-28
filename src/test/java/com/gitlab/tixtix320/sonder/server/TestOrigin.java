package com.gitlab.tixtix320.sonder.server;

import com.gitlab.tixtix320.kiwi.api.observable.Observable;
import com.gitlab.tixtix320.sonder.api.common.Origin;
import com.gitlab.tixtix320.sonder.api.extra.ClientID;

/**
 * @author Tigran.Sargsyan on 30-Jan-19
 */
@Origin("super")
public interface TestOrigin {

	@Origin("")
	Observable<Integer> foo(String message, @ClientID long id);
}
