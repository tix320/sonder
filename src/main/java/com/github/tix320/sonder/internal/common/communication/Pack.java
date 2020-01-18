package com.github.tix320.sonder.internal.common.communication;

import java.nio.channels.ReadableByteChannel;

public final class Pack {

	private final byte[] headers;

	private final ReadableByteChannel channel;

	private final long contentLength;

	public Pack(byte[] headers, ReadableByteChannel channel, long contentLength) {
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

	public long getContentLength() {
		return contentLength;
	}
}
