package com.github.tix320.sonder.internal.common.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;

import com.github.tix320.kiwi.api.observable.Observable;
import com.github.tix320.kiwi.api.observable.subject.Subject;
import com.github.tix320.kiwi.api.util.None;

public class LimitedReadableByteChannel implements ReadableByteChannel {

	private final ReadableByteChannel channel;

	private long remaining;

	private boolean isOpen;

	private final Subject<None> finishEvent;

	public LimitedReadableByteChannel(ReadableByteChannel channel, long limit) {
		if (limit <= 0) {
			throw new IllegalArgumentException("Limit: " + limit);
		}

		this.channel = channel;
		this.remaining = limit;
		this.isOpen = true;
		this.finishEvent = Subject.buffered(1);
	}

	@Override
	public synchronized int read(ByteBuffer dst)
			throws IOException {
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
			finishEvent.next(None.SELF);
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

	public Observable<None> onFinish() {
		return finishEvent.asObservable();
	}

	public long getRemaining() {
		return remaining;
	}

	public synchronized void readAllInVain()
			throws IOException {
		ByteBuffer buffer = ByteBuffer.allocate(1024 * 64);
		while (remaining > 0) {
			int read = channel.read(buffer);
			if (read < 0) {
				break;
			}
			remaining -= read;
			buffer.clear();
		}
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
