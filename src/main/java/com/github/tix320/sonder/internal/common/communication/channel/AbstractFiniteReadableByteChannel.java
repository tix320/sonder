package com.github.tix320.sonder.internal.common.communication.channel;

import com.github.tix320.kiwi.observable.MonoObservable;
import com.github.tix320.kiwi.publisher.MonoPublisher;
import com.github.tix320.kiwi.publisher.Publisher;
import com.github.tix320.skimp.api.object.None;
import com.github.tix320.sonder.api.common.communication.channel.FiniteReadableByteChannel;

/**
 * @author : Tigran Sargsyan
 * @since : 17.02.2021
 **/
public abstract class AbstractFiniteReadableByteChannel extends AbstractCloseableChannel
		implements FiniteReadableByteChannel {

	private final MonoPublisher<None> completeness;

	public AbstractFiniteReadableByteChannel() {
		completeness = Publisher.mono();
	}

	@Override
	protected final void implCloseChannel() {
		completeness.complete();
	}

	@Override
	public final MonoObservable<None> completeness() {
		return completeness.asObservable();
	}

	protected final void fireCompleted() {
		completeness.publish(None.SELF);
	}
}
