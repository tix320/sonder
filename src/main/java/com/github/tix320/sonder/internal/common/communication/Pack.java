package com.github.tix320.sonder.internal.common.communication;

import com.github.tix320.sonder.api.common.communication.CertainReadableByteChannel;

public final class Pack {

	private final byte[] headers;

	private final CertainReadableByteChannel channel;

	public Pack(byte[] headers, CertainReadableByteChannel channel) {
		this.headers = headers;
		this.channel = channel;
	}

	public byte[] getHeaders() {
		return headers;
	}

	public CertainReadableByteChannel channel() {
		return channel;
	}
}
