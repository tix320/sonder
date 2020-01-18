package com.github.tix320.sonder.internal.common.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

public class EmptyReadableByteChannel implements ReadableByteChannel {

	public static final EmptyReadableByteChannel SELF = new EmptyReadableByteChannel();

	private EmptyReadableByteChannel() {
	}

	@Override
	public int read(ByteBuffer dst)
			throws IOException {
		return -1;
	}

	@Override
	public boolean isOpen() {
		return true;
	}

	@Override
	public void close()
			throws IOException {

	}
}
