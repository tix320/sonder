package com.gitlab.tixtix320.sonder.internal.client;

import java.io.Closeable;

import com.gitlab.tixtix320.kiwi.api.observable.Observable;
import com.gitlab.tixtix320.sonder.internal.common.communication.Pack;

public interface ServerConnection extends Closeable {

	Observable<Pack> incomingRequests();

	void send(Pack pack);
}
