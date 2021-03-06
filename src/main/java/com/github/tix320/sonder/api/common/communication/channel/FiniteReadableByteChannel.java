package com.github.tix320.sonder.api.common.communication.channel;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;

import com.github.tix320.kiwi.api.reactive.observable.MonoObservable;
import com.github.tix320.skimp.api.object.None;

/**
 * Channel hold information about content length and remaining length.
 * Maximum bytes, which you can read, is equal to content length, after this it will be empty.
 *
 * @author Tigran Sargsyan on 23-Mar-20.
 */
public interface FiniteReadableByteChannel extends ReadableByteChannel {

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
	 * Observable to subscribe completed state, i.e. all bytes was read.
	 *
	 * @return observable
	 */
	MonoObservable<None> completeness();
}
