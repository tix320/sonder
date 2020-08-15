package com.github.tix320.sonder.RPC;

import com.github.tix320.kiwi.api.reactive.observable.MonoObservable;
import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.kiwi.api.util.None;
import com.github.tix320.sonder.api.common.rpc.Origin;
import com.github.tix320.sonder.api.common.rpc.Response;
import com.github.tix320.sonder.api.common.rpc.Subscription;

@Origin("foo")
public interface ClientService {

	@Origin
	@Subscription
	Observable<Integer> numbers();

	@Origin
	MonoObservable<Integer> putByes(byte[] bytes);

	@Origin
	MonoObservable<Integer> getStringLength(String s);

	@Origin
	MonoObservable<Response<None>> throwAnyException();

	@Origin
	MonoObservable<None> throwAnyExceptionWithoutHandle();

	@Origin
	MonoObservable<Response<Integer>> getAnyValue();

	@Origin
	MonoObservable<Integer> testFactory();
}
