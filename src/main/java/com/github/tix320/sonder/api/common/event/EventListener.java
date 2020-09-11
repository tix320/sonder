package com.github.tix320.sonder.api.common.event;

import com.github.tix320.kiwi.api.reactive.observable.Observable;

public interface EventListener {

	<T extends SonderEvent> Observable<T> on(Class<T> clazz);
}
