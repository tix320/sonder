package com.github.tix320.sonder.api.common.communication;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;

import com.github.tix320.sonder.internal.common.communication.BaseCertainReadableByteChannel;

public final class ByteArrayReadableChannel extends BaseCertainReadableByteChannel {

	private final byte[] array;

	private int position;

	public ByteArrayReadableChannel(byte[] array) {
		this.array = array;
		this.position = 0;
	}

	@Override
	public synchronized int read(ByteBuffer dst) throws IOException {
		if (dst.isReadOnly()) {
			throw new IllegalArgumentException("Read only buffer");
		}

		if (!isOpen()) {
			throw new ClosedChannelException();
		}

		if (position == array.length) {
			return -1;
		}

		int remaining = dst.remaining();
		int readCount = Math.min(remaining, array.length);
		dst.put(array, position, readCount);
		position += readCount;

		if (getRemaining() == 0) {
			fireCompleted();
		}

		return readCount;
	}

	@Override
	public long getContentLength() {
		return array.length;
	}

	@Override
	public long getRemaining() {
		synchronized (this) {
			return array.length - position;
		}
	}

	@Override
	public byte[] readAll() {
		synchronized (this) {
			if (position != 0) {
				throw new IllegalStateException("readAll not allowed, when any bytes already was read");
			}

			position = array.length;
			fireCompleted();
			return array;
		}
	}

	@Override
	public void readRemainingInVain() throws IOException {
		synchronized (this) {
			position = array.length;
			fireCompleted();
		}
	}
}
