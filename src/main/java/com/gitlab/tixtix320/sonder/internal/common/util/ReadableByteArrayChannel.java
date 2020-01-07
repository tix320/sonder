package com.gitlab.tixtix320.sonder.internal.common.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

public class ReadableByteArrayChannel implements ReadableByteChannel {

	private final byte[] array;

	private int position;

	public ReadableByteArrayChannel(byte[] array) {
		this.array = array;
		this.position = 0;
	}

	@Override
	public int read(ByteBuffer dst)
			throws IOException {
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
	public boolean isOpen() {
		return true;
	}

	@Override
	public void close() {

	}
}
