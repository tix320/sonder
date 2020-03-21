package com.github.tix320.sonder.internal.event;

import com.github.tix320.kiwi.api.reactive.observable.Observable;

/**
 * @author Tigran Sargsyan on 22-Mar-20.
 */
public interface SonderEventDispatcher<S> {

	<T extends S> Observable<T> on(Class<T> clazz);

	void fire(S eventObject);
}
