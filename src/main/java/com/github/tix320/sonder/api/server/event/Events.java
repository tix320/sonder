package com.github.tix320.sonder.api.server.event;

import com.github.tix320.kiwi.observable.Observable;
import com.github.tix320.sonder.api.common.Client;

public interface Events {

	Observable<Client> newConnections();

	Observable<Client> deadConnections();
}
