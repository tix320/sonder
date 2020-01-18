package com.github.tix320.sonder.api.common.communication;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;

import com.github.tix320.sonder.internal.common.util.ReadableByteArrayChannel;

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

	public ReadableByteChannel channel() {
		return new ReadableByteArrayChannel(content);
	}

	@Override
	public long getContentLength() {
		return content.length;
	}

	@Override
	public byte[] readAll()
			throws IOException {
		return content;
	}

	@Override
	public void readAllInVain()
			throws IOException {

	}
}
