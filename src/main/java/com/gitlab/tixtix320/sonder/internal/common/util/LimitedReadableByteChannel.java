package com.gitlab.tixtix320.sonder.internal.common.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

import com.gitlab.tixtix320.kiwi.api.observable.Observable;
import com.gitlab.tixtix320.kiwi.api.observable.subject.Subject;
import com.gitlab.tixtix320.kiwi.api.util.None;

public class LimitedReadableByteChannel implements ReadableByteChannel {

	private final ReadableByteChannel channel;

	private final int limit;

	private int readCount;

	private final Subject<None> finishEvent;

	public LimitedReadableByteChannel(ReadableByteChannel channel, int limit) {
		if (limit <= 0) {
			throw new IllegalArgumentException("Limit: " + limit);
		}

		this.channel = channel;
		this.limit = limit;
		this.readCount = 0;
		this.finishEvent = Subject.single();
	}

	@Override
	public synchronized int read(ByteBuffer dst)
			throws IOException {
		if (isFinished()) {
			return -1;
		}

		int needToRead = dst.remaining();
		int remaining = limit - readCount;

		int limit = dst.limit();
		if (remaining < needToRead) {
			dst.limit(dst.position() + remaining);
		}

		int bytes = channel.read(dst);
		dst.limit(limit);
		readCount += bytes;

		if (isFinished()) {
			finishEvent.next(None.SELF);
		}

		return bytes;
	}

	@Override
	public synchronized boolean isOpen() {
		return readCount != limit;
	}

	@Override
	public synchronized void close()
			throws IOException {
		readCount = limit;
	}

	private boolean isFinished() {
		return readCount == limit;
	}

	public Observable<None> onFinish() {
		return finishEvent.asObservable();
	}
}
