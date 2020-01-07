package com.gitlab.tixtix320.sonder.api.common.communication;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

public class ChannelTransfer implements Transfer {

	private final Headers headers;

	private final ReadableByteChannel channel;

	private final int contentLength;

	public ChannelTransfer(Headers headers, ReadableByteChannel channel, int contentLength) {
		this.headers = headers;
		this.channel = channel;
		this.contentLength = contentLength;
	}

	@Override
	public Headers getHeaders() {
		return headers;
	}

	@Override
	public ReadableByteChannel channel() {
		return channel;
	}

	@Override
	public int getContentLength() {
		return contentLength;
	}

	@Override
	public byte[] readAll()
			throws IOException {
		ReadableByteChannel channel = channel();
		ByteBuffer buffer = ByteBuffer.allocate(getContentLength());
		while (buffer.hasRemaining()) {
			int read = channel.read(buffer);
			if (read < 0) {
				throw new IllegalStateException(
						String.format("Content channel ended, but but still remaining %s bytes", buffer.remaining()));
			}
		}
		return buffer.array();
	}
}
