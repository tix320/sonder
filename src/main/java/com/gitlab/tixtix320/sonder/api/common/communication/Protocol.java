package com.gitlab.tixtix320.sonder.api.common.communication;

import java.io.Closeable;
import java.io.IOException;

import com.gitlab.tixtix320.kiwi.api.observable.Observable;

public interface Protocol extends Closeable {

	void handleIncomingTransfer(Transfer transfer)
			throws IOException;

	Observable<Transfer> outgoingTransfers();

	String getName();
}
