package com.github.tix320.sonder.internal.common.communication;

import com.github.tix320.sonder.api.common.communication.channel.FiniteReadableByteChannel;

public final class Pack {

	private final byte[] headers;

	private final FiniteReadableByteChannel channel;

	public Pack(byte[] headers, FiniteReadableByteChannel channel) {
		this.headers = headers;
		this.channel = channel;
	}

	public byte[] getHeaders() {
		return headers;
	}

	public FiniteReadableByteChannel contentChannel() {
		return channel;
	}
}
