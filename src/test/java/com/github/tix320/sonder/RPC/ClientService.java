package com.github.tix320.sonder.RPC;

import com.github.tix320.kiwi.api.reactive.observable.MonoObservable;
import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.sonder.api.common.rpc.Origin;
import com.github.tix320.sonder.api.common.rpc.Subscription;

@Origin("foo")
public interface ClientService {

	@Origin("")
	MonoObservable<String> getRemoteObjectId();

	@Origin("lol")
	@Subscription
	Observable<Integer> numbers();
}
