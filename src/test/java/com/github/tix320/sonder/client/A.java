package com.github.tix320.sonder.client;

import java.util.List;

import com.github.tix320.sonder.api.common.rpc.Origin;
import com.github.tix320.kiwi.api.observable.Observable;

/**
 * @author Tigran.Sargsyan on 30-Jan-19
 */
@Origin("chat")
public interface A {

	@Origin("message")
	Observable<List<Integer>> send(String message);
}
