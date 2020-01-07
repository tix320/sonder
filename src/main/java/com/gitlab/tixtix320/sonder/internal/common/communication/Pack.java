package com.gitlab.tixtix320.sonder.internal.common.communication;

import java.nio.channels.ReadableByteChannel;

public final class Pack {

	private final byte[] headers;

	private final ReadableByteChannel channel;

	private final int contentLength;

	public Pack(byte[] headers, ReadableByteChannel channel, int contentLength) {
		this.headers = headers;
		this.channel = channel;
		this.contentLength = contentLength;
	}

	public byte[] getHeaders() {
		return headers;
	}

	public ReadableByteChannel channel() {
		return channel;
	}

	public int getContentLength() {
		return contentLength;
	}
}
