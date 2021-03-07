package com.github.tix320.sonder.api.common.communication.channel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;

import com.github.tix320.sonder.internal.common.communication.channel.AbstractFiniteReadableByteChannel;

public final class EmptyReadableByteChannel extends AbstractFiniteReadableByteChannel {

	public static final EmptyReadableByteChannel SELF = new EmptyReadableByteChannel();

	private EmptyReadableByteChannel() {
		fireCompleted();
	}

	@Override
	public int read(ByteBuffer dst) throws IOException {
		if (!isOpen()) {
			throw new ClosedChannelException();
		}

		return -1;
	}

	@Override
	public byte[] readAllBytes() throws IOException {
		if (!isOpen()) {
			throw new ClosedChannelException();
		}

		return new byte[0];
	}

	@Override
	public long getContentLength() {
		return 0;
	}

	@Override
	public long getRemaining() {
		return 0;
	}
}
