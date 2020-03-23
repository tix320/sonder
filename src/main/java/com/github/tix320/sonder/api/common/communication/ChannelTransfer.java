package com.github.tix320.sonder.api.common.communication;

/**
 * The default implementation of transfer.
 */
public final class ChannelTransfer implements Transfer {

	private final Headers headers;

	private final CertainReadableByteChannel channel;

	public ChannelTransfer(Headers headers, CertainReadableByteChannel channel) {
		this.headers = headers;
		this.channel = channel;
	}

	@Override
	public Headers getHeaders() {
		return headers;
	}

	@Override
	public CertainReadableByteChannel channel() {
		return channel;
	}

	@Override
	public String toString() {
		return "ChannelTransfer{" + "headers=" + headers + '}';
	}
}
