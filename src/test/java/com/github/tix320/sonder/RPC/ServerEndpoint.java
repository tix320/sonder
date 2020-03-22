package com.github.tix320.sonder.RPC;


import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.kiwi.api.reactive.publisher.Publisher;
import com.github.tix320.sonder.api.common.rpc.Endpoint;
import com.github.tix320.sonder.api.common.rpc.Subscription;

@Endpoint("foo")
public class ServerEndpoint {

	public static Publisher<Integer> publisher = Publisher.simple();

	@Endpoint("")
	public String getObject() {
		return "lmfao";
	}

	@Endpoint("lol")
	@Subscription
	public Observable<Integer> numbers() {
		return publisher.asObservable();
	}
}
