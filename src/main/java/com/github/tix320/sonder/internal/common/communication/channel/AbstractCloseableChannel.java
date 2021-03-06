package com.github.tix320.sonder.internal.common.communication.channel;

import java.io.IOException;
import java.nio.channels.Channel;

/**
 * @author : Tigran Sargsyan
 * @since : 06.03.2021
 **/
public abstract class AbstractCloseableChannel implements Channel {

	private final Object closeLock = new Object();
	private volatile boolean closed;

	protected AbstractCloseableChannel() {
	}

	/**
	 * Closes this channel.
	 *
	 * <p> If the channel has already been closed then this method returns
	 * immediately.  Otherwise it marks the channel as closed and then invokes
	 * the {@link #implCloseChannel implCloseChannel} method in order to
	 * complete the close operation.  </p>
	 *
	 * @throws IOException If an I/O error occurs
	 */
	@Override
	public final void close() throws IOException {
		synchronized (closeLock) {
			if (closed) {
				return;
			}
			closed = true;
			implCloseChannel();
		}
	}

	@Override
	public final boolean isOpen() {
		return !closed;
	}

	/**
	 * Closes this channel.
	 *
	 * <p> This method is invoked by the {@link #close close} method in order
	 * to perform the actual work of closing the channel.  This method is only
	 * invoked if the channel has not yet been closed, and it is never invoked
	 * more than once.
	 *
	 * <p> An implementation of this method must arrange for any other thread
	 * that is blocked in an I/O operation upon this channel to return
	 * immediately, either by throwing an exception or by returning normally.
	 * </p>
	 *
	 * @throws IOException If an I/O error occurs while closing the channel
	 */
	protected abstract void implCloseChannel() throws IOException;
}
