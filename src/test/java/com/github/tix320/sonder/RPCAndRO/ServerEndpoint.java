package com.github.tix320.sonder.RPCAndRO;


import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.kiwi.api.reactive.publisher.Publisher;
import com.github.tix320.sonder.api.common.rpc.Endpoint;
import com.github.tix320.sonder.api.common.rpc.Subscribe;

@Endpoint("foo")
public class ServerEndpoint {

	public static Publisher<Integer> publisher = Publisher.simple();

	@Endpoint("")
	public String getObject() {
		RemoteObjectImpl remoteObject = new RemoteObjectImpl();
		String objectId = "lmfao";
		RPCAndROTest.sonderServer.registerObject(remoteObject, objectId);
		return objectId;
	}

	@Endpoint("lol")
	@Subscribe
	public Observable<Integer> numbers() {
		return publisher.asObservable();
	}
}
