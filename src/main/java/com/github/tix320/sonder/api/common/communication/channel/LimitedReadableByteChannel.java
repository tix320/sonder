package com.github.tix320.sonder.api.common.communication.channel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;

import com.github.tix320.sonder.internal.common.communication.channel.AbstractFiniteReadableByteChannel;

public final class LimitedReadableByteChannel extends AbstractFiniteReadableByteChannel {

	protected final ReadableByteChannel source;

	private final long limit;

	protected volatile long remaining;

	public LimitedReadableByteChannel(ReadableByteChannel source, long limit) {
		if (limit <= 0) {
			throw new IllegalArgumentException("Limit: " + limit);
		}

		this.limit = limit;
		this.source = source;
		this.remaining = limit;
	}

	@Override
	public synchronized int read(ByteBuffer dst) throws IOException {
		if (!isOpen()) {
			throw new ClosedChannelException();
		}

		if (isCompleted()) {
			return -1;
		}

		int needToRead = dst.remaining();

		int limit = dst.limit();
		if (this.remaining < needToRead) {
			dst.limit(dst.position() + (int) this.remaining);
		}

		int bytes;
		try {
			bytes = source.read(dst);
		} finally {
			dst.limit(limit); // reset limit to initial
		}

		if (bytes == -1) {
			throw new IOException(String.format("Wrapped channel ended, but still remaining %s bytes", this.remaining));
		}

		this.remaining -= bytes;

		if (isCompleted()) {
			fireCompleted();
		}

		return bytes;
	}

	@Override
	public final synchronized byte[] readAllBytes() throws IOException {
		if (!isOpen()) {
			throw new ClosedChannelException();
		}

		if (limit > Integer.MAX_VALUE) {
			throw new UnsupportedOperationException(
					"Cannot read all bytes, due there are larger than Integer.MAX_VALUE");
		}

		ByteBuffer buffer = ByteBuffer.allocate((int) remaining);
		while (buffer.hasRemaining()) {
			read(buffer);
		}

		return buffer.array();
	}

	@Override
	public final long getContentLength() {
		return limit;
	}

	@Override
	public final long getRemaining() {
		return remaining;
	}

	private boolean isCompleted() {
		return remaining == 0;
	}

	@Override
	public String toString() {
		return "LimitedReadableByteChannel{" + ", limit=" + limit + ", remaining=" + remaining + '}';
	}
}
