package com.github.tix320.sonder.internal.client;

import java.io.Closeable;

import com.github.tix320.sonder.internal.common.communication.Pack;
import com.github.tix320.kiwi.api.observable.Observable;

public interface ServerConnection extends Closeable {

	Observable<Pack> incomingRequests();

	void send(Pack pack);
}
