package com.github.tix320.sonder.api.common.communication;

/**
 * The main class for transferring any data between clients and server.
 * It includes headers {@link Headers} and channel for reading content {@link CertainReadableByteChannel}.
 * Headers basically used for implementing protocol logic. {@link Protocol}
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
	 *
	 * @see CertainReadableByteChannel
	 */
	CertainReadableByteChannel channel();
}
