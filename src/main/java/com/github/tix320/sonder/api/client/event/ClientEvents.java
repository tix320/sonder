package com.github.tix320.sonder.api.client.event;

import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.sonder.api.client.ConnectionState;

public interface ClientEvents {

	Observable<ConnectionState> connectionState();
}
