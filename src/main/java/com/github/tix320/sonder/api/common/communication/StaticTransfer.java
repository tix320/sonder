package com.github.tix320.sonder.api.common.communication;

import java.util.Arrays;

/**
 * The byte array implementation of transfer.
 */
public final class StaticTransfer implements Transfer {

	private final Headers headers;

	private final byte[] content;

	public StaticTransfer(Headers headers, byte[] content) {
		this.headers = headers;
		this.content = content;
	}

	public Headers getHeaders() {
		return headers;
	}

	public CertainReadableByteChannel channel() {
		return new ReadableByteArrayChannel(content);
	}

	@Override
	public String toString() {
		return "StaticTransfer{" + "headers=" + headers + ", content=" + Arrays.toString(content) + '}';
	}
}
