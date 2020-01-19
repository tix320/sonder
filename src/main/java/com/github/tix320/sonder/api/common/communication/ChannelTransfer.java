package com.github.tix320.sonder.api.common.communication;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

public class ChannelTransfer implements Transfer {

	private final Headers headers;

	private final ReadableByteChannel channel;

	private final long contentLength;

	public ChannelTransfer(Headers headers, ReadableByteChannel channel, long contentLength) {
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
	public long getContentLength() {
		return contentLength;
	}

	@Override
	public byte[] readAll()
			throws IOException {
		if (contentLength > Integer.MAX_VALUE) {
			throw new UnsupportedOperationException(
					"Cannot read all bytes, due there are larger than Integer.MAX_VALUE");
		}

		ReadableByteChannel channel = channel();
		ByteBuffer buffer = ByteBuffer.allocate((int) contentLength);
		while (buffer.hasRemaining()) {
			int read = channel.read(buffer);
			if (read < 0) {
				throw new IllegalStateException(
						String.format("Content channel ended, but still remaining %s bytes", buffer.remaining()));
			}
		}
		return buffer.array();
	}

	@Override
	public void readAllInVain()
			throws IOException {
		ReadableByteChannel channel = channel();
		ByteBuffer buffer = ByteBuffer.allocate(1024 * 64);
		long remaining = contentLength;
		while (remaining > 0) {
			int read = channel.read(buffer);
			if (read < 0) {
				throw new IllegalStateException(
						String.format("Content channel ended, but still remaining %s bytes", remaining));
			}
			remaining -= read;
			buffer.clear();
		}
	}
}
