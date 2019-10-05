package com.gitlab.tixtix320.sonder.api.common.communication;

import java.io.Closeable;

import com.gitlab.tixtix320.kiwi.api.observable.Observable;

public interface Protocol extends Closeable {

	void handleTransfer(Transfer transfer);

	Observable<Transfer> transfers();

	String getName();
}
