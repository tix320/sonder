package com.github.tix320.sonder.api.common.communication;

import com.github.tix320.sonder.api.common.communication.channel.FiniteReadableByteChannel;

/**
 * The default implementation of transfer.
 */
public final class ChannelTransfer implements Transfer {

	private final Headers headers;

	private final FiniteReadableByteChannel channel;

	public ChannelTransfer(Headers headers, FiniteReadableByteChannel channel) {
		this.headers = headers;
		this.channel = channel;
	}

	@Override
	public Headers getHeaders() {
		return headers;
	}

	@Override
	public FiniteReadableByteChannel channel() {
		return channel;
	}

	@Override
	public String toString() {
		return "ChannelTransfer{" + "headers=" + headers + '}';
	}
}
