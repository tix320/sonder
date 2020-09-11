package com.github.tix320.sonder.api.common.communication;

import java.io.IOException;
import java.nio.ByteBuffer;

public class ReadableByteArrayChannel implements CertainReadableByteChannel {

	private final byte[] array;

	private int position;

	private boolean isOpen;

	public ReadableByteArrayChannel(byte[] array) {
		this.array = array;
		this.position = 0;
		this.isOpen = true;
	}

	@Override
	public synchronized int read(ByteBuffer dst) throws IOException {
		if (position == array.length) {
			return -1;
		}
		int remaining = dst.remaining();
		int readCount = Math.min(remaining, array.length);
		dst.put(array, position, readCount);
		position += readCount;
		return readCount;
	}

	@Override
	public synchronized boolean isOpen() {
		return isOpen;
	}

	@Override
	public synchronized void close() {
		isOpen = false;
	}

	@Override
	public synchronized long getContentLength() {
		return array.length;
	}

	@Override
	public synchronized long getRemaining() {
		return array.length - position;
	}

	@Override
	public synchronized byte[] readAll() throws IOException {
		if (position != 0) {
			throw new IllegalStateException("readAll not allowed, when any bytes already was read");
		}

		position = array.length;
		return array;
	}

	@Override
	public synchronized void readRemainingInVain() throws IOException {
		position = array.length;
		close();
	}
}
