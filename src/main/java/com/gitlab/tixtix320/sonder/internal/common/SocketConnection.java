package com.gitlab.tixtix320.sonder.internal.common;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import com.gitlab.tixtix320.kiwi.api.observable.Observable;
import com.gitlab.tixtix320.kiwi.api.observable.subject.Subject;


public final class SocketConnection {

	private static final byte[] HEADER = {105, 99, 111, 110, 116, 114, 111, 108};

	private static final int CONTENT_LENGTH_BYTES = 4;

	private final SocketChannel channel;

	private final Subject<byte[]> transfers;

	private final ByteArray storage;

	private boolean headerConsumed;

	private Integer contentLength;

	private final ByteBuffer buffer = ByteBuffer.allocate(1024);

	public SocketConnection(SocketChannel channel) {
		this.channel = channel;
		this.transfers = Subject.single();
		this.storage = new ByteArray();
	}

	public void write(byte[] bytes) {
		try {
			ByteBuffer buffer = ByteBuffer.allocate(HEADER.length + CONTENT_LENGTH_BYTES + bytes.length);
			buffer.put(HEADER, 0, HEADER.length);
			buffer.putInt(bytes.length);
			buffer.put(bytes, 0, bytes.length);
			buffer.flip();
			channel.write(buffer);
		}
		catch (IOException e) {
			close();
			throw new RuntimeException("Failed to send transfer", e);
		}
	}

	public void read() {
		try {
			buffer.position(0);
			int bytesCount = channel.read(buffer);
			consume(buffer, bytesCount);
		}
		catch (IOException e) {
			close();
			throw new RuntimeException("Failed to send transfer", e);
		}
	}

	public Observable<byte[]> requests() {
		return transfers.asObservable();
	}

	private void consume(ByteBuffer bytes, int length) {
		int start = 0;
		while (length != 0) {
			if (!headerConsumed) {
				int remainingHeaderBytes = HEADER.length - storage.size();

				if (length >= remainingHeaderBytes) {
					boolean isHeader = isHeader(bytes.array(), start);
					if (!isHeader) {
						new IllegalStateException("Invalid Header").printStackTrace();
						reset();
						return;
					}
					headerConsumed = true;
					storage.addBytes(bytes.array(), start, remainingHeaderBytes);
					start = start + remainingHeaderBytes;
					length = length - remainingHeaderBytes;
				}
				else {
					storage.addBytes(bytes.array(), start, length);
					start = start + length;
				}
			}
			else if (contentLength == null) {
				int remainingContentLengthBytes = CONTENT_LENGTH_BYTES - (storage.size() - HEADER.length);

				if (length >= remainingContentLengthBytes) {
					contentLength = bytes.getInt(start);
					if (contentLength <= 0) {
						new IllegalStateException(
								String.format("Invalid content length: %s", contentLength)).printStackTrace();
						reset();
						return;
					}
					storage.addBytes(bytes.array(), start, remainingContentLengthBytes);
					start = start + remainingContentLengthBytes;
					length = length - remainingContentLengthBytes;
				}
				else {
					storage.addBytes(bytes.array(), start, length);
					start = start + length;
				}
			}
			else {
				// read remaining content
				int remainingContentBytes = contentLength - (storage.size() - HEADER.length - CONTENT_LENGTH_BYTES);

				if (length >= remainingContentBytes) {
					storage.addBytes(bytes.array(), start, remainingContentBytes);
					byte[] data = storage.getBytes();
					byte[] requestData = new byte[contentLength];
					System.arraycopy(data, HEADER.length + CONTENT_LENGTH_BYTES, requestData, 0, contentLength);
					transfers.next(requestData);
					reset();
					start = start + remainingContentBytes;
					length = length - remainingContentBytes;
				}
				else {
					storage.addBytes(bytes.array(), start, length);
					start = start + length;
				}
			}
		}
	}

	private void reset() {
		storage.reset();
		headerConsumed = false;
		contentLength = null;
	}

	private static boolean isHeader(byte[] array, int start) {
		int end = start + HEADER.length;
		for (int i = start; i < end; i++) {
			if (array[i] != HEADER[i]) {
				return false;
			}
		}
		return true;
	}

	public void close() {
		try {
			channel.close();
		}
		catch (IOException e) {
			throw new RuntimeException("Cannot close socket", e);
		}
	}
}
