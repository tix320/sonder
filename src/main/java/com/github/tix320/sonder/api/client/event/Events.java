package com.github.tix320.sonder.api.client.event;

import com.github.tix320.kiwi.observable.Observable;
import com.github.tix320.skimp.api.object.None;
import com.github.tix320.sonder.api.client.ConnectionState;

public interface Events {

	Observable<ConnectionState> connectionState();

	default Observable<None> connected() {
		return connectionState().filter(connectionState -> connectionState == ConnectionState.CONNECTED)
				.map(connectionState -> None.SELF);
	}
}
