package com.github.tix320.sonder.internal.event;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.kiwi.api.reactive.publisher.Publisher;

/**
 * @author Tigran Sargsyan on 22-Mar-20.
 */
public class SimpleEventDispatcher<S> implements SonderEventDispatcher<S> {

	private final Map<Class<? extends S>, Publisher<S>> publishers = new ConcurrentHashMap<>();

	@SuppressWarnings("unchecked")
	public <T extends S> Observable<T> on(Class<T> clazz) {
		Publisher<S> publisher = publishers.computeIfAbsent(clazz, eventClass -> Publisher.simple());

		return (Observable<T>) publisher.asObservable();
	}

	@SuppressWarnings("unchecked")
	public void fire(S eventObject) {
		Class<? extends S> aClass = (Class<? extends S>) eventObject.getClass();
		Publisher<S> publisher = publishers.computeIfAbsent(aClass, eventClass -> Publisher.simple());
		publisher.publish(eventObject);
	}
}
