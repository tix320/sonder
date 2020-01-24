package com.github.tix320.sonder.api.common.communication;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;

/**
 * The main class for transferring any data between clients and server.
 * It includes headers {@link Headers} and channel for reading content.
 * Headers basically used for implementing protocol logic. {@link Protocol}
 * Also transfer holds content length in bytes, for correct reading from the channel.
 * Maximum bytes, which you can read, is equal to content length, after this it will be empty.
 */
public interface Transfer {

	/**
	 * Get headers of transfer.
	 *
	 * @return headers
	 */
	Headers getHeaders();

	/**
	 * Get channel of transfer content.
	 *
	 * @return channel
	 */
	ReadableByteChannel channel();

	/**
	 * Get length of transfer content
	 *
	 * @return content length
	 */
	long getContentLength();

	/**
	 * Read all content to byte array if possible.
	 *
	 * @return bytes
	 *
	 * @throws IOException                   if any IO errors occurs
	 * @throws UnsupportedOperationException if content length larger than {@link Integer#MAX_VALUE}
	 */
	byte[] readAll()
			throws IOException;

	/**
	 * Read all content in vain.
	 * This method may used when you do not need to read content, but you must,
	 * because of next transfers will be blocked until this is processed.
	 *
	 * @throws IOException if any IO errors occurs
	 */
	void readAllInVain()
			throws IOException;
}
