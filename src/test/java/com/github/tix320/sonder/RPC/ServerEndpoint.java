package com.github.tix320.sonder.RPC;


import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.kiwi.api.reactive.publisher.Publisher;
import com.github.tix320.skimp.api.object.None;
import com.github.tix320.sonder.api.common.rpc.Endpoint;
import com.github.tix320.sonder.api.common.rpc.Subscription;

@Endpoint("foo")
public class ServerEndpoint {

	public static Publisher<Integer> publisher = Publisher.simple();

	public int forTest;

	@Endpoint
	@Subscription
	public Observable<Integer> numbers() {
		return publisher.asObservable();
	}


	@Endpoint
	public int putByes(byte[] bytes) {
		int sum = 0;
		for (byte aByte : bytes) {
			sum += aByte;
		}
		return sum;
	}

	@Endpoint
	public Integer getStringLength(String s) {
		return s.length();
	}

	@Endpoint
	public None throwAnyException() {
		throw new RuntimeException();
	}

	@Endpoint
	public None throwAnyExceptionWithoutHandle() {
		throw new RuntimeException();
	}

	@Endpoint
	public Integer getAnyValue() {
		return 5;
	}

	@Endpoint
	public int testFactory() {
		return forTest;
	}
}
