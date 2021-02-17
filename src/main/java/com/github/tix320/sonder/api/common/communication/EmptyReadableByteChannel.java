package com.github.tix320.sonder.api.common.communication;

import java.io.IOException;
import java.nio.ByteBuffer;

import com.github.tix320.sonder.internal.common.communication.BaseCertainReadableByteChannel;

public final class EmptyReadableByteChannel extends BaseCertainReadableByteChannel {

	public static final EmptyReadableByteChannel SELF = new EmptyReadableByteChannel();

	private EmptyReadableByteChannel() {
		fireCompleted();
	}

	@Override
	public int read(ByteBuffer dst) throws IOException {
		return -1;
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
