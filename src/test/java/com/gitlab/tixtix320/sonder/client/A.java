package com.gitlab.tixtix320.sonder.client;

import java.util.List;

import com.gitlab.tixtix320.kiwi.api.observable.Observable;
import com.gitlab.tixtix320.sonder.api.common.rpc.Origin;

/**
 * @author Tigran.Sargsyan on 30-Jan-19
 */
@Origin("chat")
public interface A {

	@Origin("message")
	Observable<List<Integer>> send(String message);
}
