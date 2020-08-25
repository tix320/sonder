package com.github.tix320.sonder.api.common.event;

import com.github.tix320.kiwi.api.reactive.observable.Observable;

/**
 * @author Tigran Sargsyan on 22-Mar-20.
 */
public interface SonderEventDispatcher {

	<T extends SonderEvent> Observable<T> on(Class<T> clazz);

	void fire(SonderEvent eventObject);
}
