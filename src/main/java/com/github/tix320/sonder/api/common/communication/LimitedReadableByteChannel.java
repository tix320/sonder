package com.github.tix320.sonder.api.common.communication;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;

import com.github.tix320.kiwi.api.reactive.observable.MonoObservable;
import com.github.tix320.kiwi.api.reactive.publisher.MonoPublisher;
import com.github.tix320.kiwi.api.reactive.publisher.Publisher;
import com.github.tix320.skimp.api.object.None;

public final class LimitedReadableByteChannel implements CertainReadableByteChannel {

	private final ReadableByteChannel channel;

	private final long limit;

	private long remaining;

	private boolean isOpen;

	private final MonoPublisher<None> finishEvent;

	public LimitedReadableByteChannel(ReadableByteChannel channel, long limit) {
		if (limit <= 0) {
			throw new IllegalArgumentException("Limit: " + limit);
		}

		this.limit = limit;
		this.channel = channel;
		this.remaining = limit;
		this.isOpen = true;
		this.finishEvent = Publisher.mono();
	}

	@Override
	public synchronized int read(ByteBuffer dst) throws IOException {
		if (!isOpen()) {
			throw new ClosedChannelException();
		}

		if (isFinished()) {
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

		if (isFinished()) {
			finishEvent.publish(None.SELF);
		}

		return bytes;
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
	public MonoObservable<None> onFinish() {
		return finishEvent.asObservable().toMono();
	}

	@Override
	public synchronized long getContentLength() {
		return limit;
	}

	public synchronized long getRemaining() {
		return remaining;
	}

	@Override
	public synchronized byte[] readAll() throws IOException {
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

		finishEvent.publish(None.SELF);
		return buffer.array();
	}

	public synchronized void readRemainingInVain() throws IOException {
		if (isFinished()) {
			return;
		}

		ByteBuffer buffer = ByteBuffer.allocate(1024 * 64);
		while (remaining > 0) {
			int read = channel.read(buffer);
			if (read < 0) {
				break;
			}
			remaining -= read;
			buffer.clear();
		}

		finishEvent.publish(None.SELF);
		close();
	}

	private boolean isFinished() {
		return remaining == 0;
	}

	@Override
	public String toString() {
		return "LimitedReadableByteChannel"
			   + super.hashCode()
			   + " {remaining="
			   + remaining
			   + ", isOpen="
			   + isOpen
			   + '}';
	}
}
