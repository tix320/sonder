package com.github.tix320.sonder.api.common.topic;

import com.github.tix320.kiwi.api.reactive.observable.MonoObservable;
import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.kiwi.api.util.None;

/**
 * Topic for publishing and receiving data via topic protocols.
 *
 * @param <T> type of data.
 */
public interface Topic<T> {

	/**
	 * Publish data to topic,
	 * All clients, which are subscribed to this topic, will be received data.
	 * After sending data to all, a response will be returned for this client.
	 *
	 * @param data to publish
	 *
	 * @return observable, which will be completed after sending data to all clients.
	 */
	MonoObservable<None> publish(T data);

	/**
	 * When calling this method, the {subscribe} action will be sent.
	 * Returns observable, for consuming incoming data.
	 *
	 * @return observable of data
	 */
	Observable<T> asObservable();

	/**
	 * Get name of this topic.
	 *
	 * @return name
	 */
	String getName();
}
