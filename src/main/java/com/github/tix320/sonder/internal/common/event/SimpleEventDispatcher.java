package com.github.tix320.sonder.internal.common.event;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.kiwi.api.reactive.publisher.Publisher;
import com.github.tix320.sonder.api.common.event.SonderEvent;

/**
 * @author Tigran Sargsyan on 22-Mar-20.
 */
public class SimpleEventDispatcher implements EventDispatcher {

	private final Map<Class<? extends SonderEvent>, Publisher<SonderEvent>> publishers = new ConcurrentHashMap<>();

	@SuppressWarnings("unchecked")
	public <T extends SonderEvent> Observable<T> on(Class<T> clazz) {
		Publisher<SonderEvent> publisher = publishers.computeIfAbsent(clazz, eventClass -> Publisher.simple());

		return (Observable<T>) publisher.asObservable();
	}

	public void fire(SonderEvent eventObject) {
		Class<? extends SonderEvent> aClass = eventObject.getClass();
		Publisher<SonderEvent> publisher = publishers.computeIfAbsent(aClass, eventClass -> Publisher.simple());
		publisher.publish(eventObject);
	}
}
