package com.github.tix320.sonder.api.common.communication;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;

import com.github.tix320.kiwi.api.reactive.observable.MonoObservable;
import com.github.tix320.skimp.api.object.None;

/**
 * Channel for transferring content part in transfer objects {@link Transfer}.
 * Channel hold information about content length and remaining length.
 * Maximum bytes, which you can read, is equal to content length, after this it will be empty.
 *
 * @author Tigran Sargsyan on 23-Mar-20.
 */
public interface CertainReadableByteChannel extends ReadableByteChannel {

	MonoObservable<None> onFinish();

	/**
	 * Get length of content
	 *
	 * @return content length
	 */
	long getContentLength();

	/**
	 * Get remaining content length
	 *
	 * @return get remaining content length
	 */
	long getRemaining();

	/**
	 * Read all content to byte array if possible.
	 *
	 * @return bytes
	 *
	 * @throws IOException                   if any IO errors occurs
	 * @throws UnsupportedOperationException if content length larger than {@link Integer#MAX_VALUE}
	 * @throws IllegalStateException         if this method called after any one byte read
	 */
	byte[] readAll() throws IOException;

	/**
	 * Read all content in vain and close channel.
	 * This method may used when you do not need to read content, but you must,
	 * because of next transfers will be blocked until this is processed.
	 *
	 * @throws IOException if any IO errors occurs
	 */
	void readRemainingInVain() throws IOException;
}
