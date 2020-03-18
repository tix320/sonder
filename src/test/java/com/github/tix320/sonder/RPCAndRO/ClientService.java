package com.github.tix320.sonder.RPCAndRO;

import com.github.tix320.kiwi.api.reactive.observable.MonoObservable;
import com.github.tix320.sonder.api.common.rpc.Origin;

@Origin("foo")
public interface ClientService {

	@Origin("")
	MonoObservable<String> getRemoteObjectId();
}
