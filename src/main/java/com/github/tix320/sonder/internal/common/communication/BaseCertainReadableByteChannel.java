package com.github.tix320.sonder.internal.common.communication;

import java.io.IOException;

import com.github.tix320.kiwi.api.reactive.observable.MonoObservable;
import com.github.tix320.kiwi.api.reactive.publisher.MonoPublisher;
import com.github.tix320.kiwi.api.reactive.publisher.Publisher;
import com.github.tix320.skimp.api.object.None;
import com.github.tix320.sonder.api.common.communication.CertainReadableByteChannel;

/**
 * @author : Tigran Sargsyan
 * @since : 17.02.2021
 **/
public abstract class BaseCertainReadableByteChannel implements CertainReadableByteChannel {

	private final MonoPublisher<None> completeness;

	private volatile boolean isOpen;

	public BaseCertainReadableByteChannel() {
		completeness = Publisher.mono();
		isOpen = true;
	}

	@Override
	public final boolean isOpen() {
		synchronized (this) {
			return isOpen;
		}
	}

	@Override
	public final void close() {
		synchronized (this) {
			if (isOpen) {
				isOpen = false;
				completeness.complete();
			}
		}
	}

	@Override
	public final MonoObservable<None> completeness() {
		return completeness.asObservable();
	}

	protected final void fireCompleted() {
		completeness.publish(None.SELF);
	}
}
