package com.github.tix320.sonder.RPC;

import com.github.tix320.kiwi.api.reactive.observable.MonoObservable;
import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.sonder.api.common.rpc.Origin;
import com.github.tix320.sonder.api.common.rpc.Subscribe;

@Origin("foo")
public interface ClientService {

	@Origin("")
	MonoObservable<String> getRemoteObjectId();

	@Origin("lol")
	@Subscribe
	Observable<Integer> numbers();
}
