package com.gitlab.tixtix320.sonder.api.common.topic;

import com.gitlab.tixtix320.kiwi.api.observable.Observable;
import com.gitlab.tixtix320.kiwi.api.util.None;

public interface Topic<T> {

	Observable<None> publish(T data);

	Observable<T> asObservable();

	String getName();
}
