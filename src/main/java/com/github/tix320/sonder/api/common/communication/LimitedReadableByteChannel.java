package com.github.tix320.sonder.api.common.communication;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;

import com.github.tix320.sonder.internal.common.communication.BaseCertainReadableByteChannel;

public class LimitedReadableByteChannel extends BaseCertainReadableByteChannel {

	protected final ReadableByteChannel channel;

	private final long limit;

	protected volatile long remaining;

	public LimitedReadableByteChannel(ReadableByteChannel channel, long limit) {
		if (limit <= 0) {
			throw new IllegalArgumentException("Limit: " + limit);
		}

		this.limit = limit;
		this.channel = channel;
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
		long remaining = this.remaining;

		int limit = dst.limit();
		if (remaining < needToRead) {
			dst.limit(dst.position() + (int) remaining);
		}

		int bytes = channel.read(dst);
		dst.limit(limit);
		this.remaining -= bytes;

		if (isCompleted()) {
			fireCompleted();
		}

		return bytes;
	}

	@Override
	public final long getContentLength() {
		return limit;
	}

	public final long getRemaining() {
		return remaining;
	}

	@Override
	public final synchronized byte[] readAll() throws IOException {
		if (!isOpen()) {
			throw new ClosedChannelException();
		}

		if (limit > Integer.MAX_VALUE) {
			throw new UnsupportedOperationException(
					"Cannot read all bytes, due there are larger than Integer.MAX_VALUE");
		}

		if (remaining != limit) {
			throw new IllegalStateException("readAll not allowed, when any bytes already was read");
		}

		ByteBuffer buffer = ByteBuffer.allocate((int) limit);
		while (buffer.hasRemaining()) {
			int read = read(buffer);
			if (read < 0) {
				throw new IllegalStateException(
						String.format("Content channel ended, but still remaining %s bytes", buffer.remaining()));
			}
		}

		return buffer.array();
	}

	@Override
	public final synchronized void readRemainingInVain() throws IOException {
		ByteBuffer buffer = ByteBuffer.allocate(1024 * 64);

		while (read(buffer) != -1) {
			buffer.clear();
		}
	}

	private boolean isCompleted() {
		return remaining == 0;
	}

	@Override
	public String toString() {
		return "LimitedReadableByteChannel{" + ", limit=" + limit + ", remaining=" + remaining + '}';
	}
}
