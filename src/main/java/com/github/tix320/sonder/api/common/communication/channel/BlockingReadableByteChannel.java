package com.github.tix320.sonder.api.common.communication.channel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import com.github.tix320.kiwi.observable.Observable;
import com.github.tix320.kiwi.publisher.Publisher;
import com.github.tix320.kiwi.publisher.SimplePublisher;
import com.github.tix320.skimp.api.object.None;
import com.github.tix320.sonder.internal.common.communication.channel.AbstractCloseableChannel;

/**
 * @author : Tigran Sargsyan
 * @since : 20.02.2021
 **/
public final class BlockingReadableByteChannel extends AbstractCloseableChannel implements ReadableByteChannel {

	private final ReadableByteChannel source;

	private final SimplePublisher<None> emptiness;

	private volatile BlockingQueue<Boolean> blockingQueue;

	private final Object wakeupLock = new Object();

	public BlockingReadableByteChannel(ReadableByteChannel source) {
		this.source = source;
		this.emptiness = Publisher.simple();
		this.blockingQueue = null;
	}

	@Override
	public synchronized int read(ByteBuffer dst) throws IOException {
		if (!isOpen()) {
			throw new ClosedChannelException();
		}

		int read = source.read(dst);

		if (read != 0) {
			return read;
		} else {
			blockingQueue = new LinkedBlockingQueue<>(1);

			emptiness.publish(None.SELF);

			boolean normal;
			try {
				normal = blockingQueue.take();
			} catch (InterruptedException e) {
				throw new IOException("Thread was interrupted while read.", e);
			}

			if (normal) {
				return read(dst);
			} else {
				throw new ClosedByInterruptException();
			}
		}
	}

	@Override
	protected void implCloseChannel() throws IOException {
		synchronized (wakeupLock) {
			wakeup(false);
			emptiness.complete();
		}
	}

	public void notifyForAvailability() {
		synchronized (wakeupLock) {
			wakeup(true);
		}
	}

	public Observable<None> emptiness() {
		return emptiness.asObservable();
	}

	private void wakeup(boolean normal) {
		BlockingQueue<Boolean> queue = this.blockingQueue;
		if (queue != null) {
			queue.offer(normal);
		}
	}
}
