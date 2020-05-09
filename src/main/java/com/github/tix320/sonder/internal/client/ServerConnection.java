package com.github.tix320.sonder.internal.client;

import java.io.Closeable;
import java.io.IOException;

import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.sonder.internal.common.communication.Pack;

public interface ServerConnection extends Closeable {

	void connect() throws IOException;

	Observable<Pack> incomingRequests();

	void send(Pack pack);
}
