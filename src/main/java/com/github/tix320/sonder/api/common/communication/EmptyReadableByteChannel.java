package com.github.tix320.sonder.api.common.communication;

import java.io.IOException;
import java.nio.ByteBuffer;

import com.github.tix320.kiwi.api.reactive.observable.MonoObservable;
import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.skimp.api.object.None;

public final class EmptyReadableByteChannel implements CertainReadableByteChannel {

	public static final EmptyReadableByteChannel SELF = new EmptyReadableByteChannel();

	private EmptyReadableByteChannel() {
	}

	@Override
	public int read(ByteBuffer dst) throws IOException {
		return -1;
	}

	@Override
	public boolean isOpen() {
		return true;
	}

	@Override
	public void close() throws IOException {

	}

	@Override
	public MonoObservable<None> onFinish() {
		return Observable.of(None.SELF);
	}

	@Override
	public long getContentLength() {
		return 0;
	}

	@Override
	public long getRemaining() {
		return 0;
	}

	@Override
	public byte[] readAll() throws IOException {
		return new byte[0];
	}

	@Override
	public void readRemainingInVain() throws IOException {

	}
}