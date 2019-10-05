package com.gitlab.tixtix320.sonder.api.common.topic;

import com.gitlab.tixtix320.kiwi.api.observable.Observable;

public interface TopicPublisher<T> {

	Observable<Void> publish(T data);

	Observable<T> asObservable();

	String getTopic();
}
