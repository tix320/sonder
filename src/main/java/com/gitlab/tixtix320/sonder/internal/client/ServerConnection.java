package com.gitlab.tixtix320.sonder.internal.client;

import java.io.Closeable;

import com.gitlab.tixtix320.kiwi.api.observable.Observable;

public interface ServerConnection extends Closeable {

	Observable<byte[]> incomingRequests();

	void send(byte[] data);
}
