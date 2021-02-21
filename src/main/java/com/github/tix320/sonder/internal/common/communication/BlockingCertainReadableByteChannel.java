package com.github.tix320.sonder.internal.common.communication;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.concurrent.CountDownLatch;

import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.kiwi.api.reactive.publisher.Publisher;
import com.github.tix320.kiwi.api.reactive.publisher.SimplePublisher;
import com.github.tix320.skimp.api.object.None;
import com.github.tix320.sonder.api.common.communication.LimitedReadableByteChannel;

/**
 * @author : Tigran Sargsyan
 * @since : 20.02.2021
 **/
public class BlockingCertainReadableByteChannel extends LimitedReadableByteChannel {

	private final SimplePublisher<None> emptiness;

	private volatile CountDownLatch latch;

	public BlockingCertainReadableByteChannel(ReadableByteChannel channel, long limit) {
		super(channel, limit);
		this.emptiness = Publisher.simple();
		this.latch = null;
		completeness().subscribe(none -> emptiness.complete());
	}

	@Override
	public synchronized int read(ByteBuffer dst) throws IOException {
		int read = super.read(dst);

		if (read != 0) {
			return read;
		}

		latch = new CountDownLatch(1);

		emptiness.publish(None.SELF);
		try {
			latch.await();
		} catch (InterruptedException e) {
			readRemainingInVain();
			close();
			throw new IllegalStateException("Thread was interrupted while read. Channel closed...");
		}

		return read(dst);
	}

	public Observable<None> emptiness() {
		return emptiness.asObservable();
	}

	public void notifyForAvailability() {
		CountDownLatch latch = this.latch;
		if (latch != null) {
			latch.countDown();
		}
	}
}
