package io.titix.sonder.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import com.gitlab.tixtix320.kiwi.observable.Observable;
import com.gitlab.tixtix320.kiwi.observable.subject.Subject;

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
			buffer.clear();
			int bytesCount = channel.read(buffer);
			consume(buffer, 0, bytesCount);
		}
		catch (IOException e) {
			close();
			throw new RuntimeException("Failed to send transfer", e);
		}
	}

	public Observable<byte[]> requests() {
		return transfers.asObservable();
	}

	private void consume(ByteBuffer bytes, int start, int length) {
		if (length == 0) {
			return;
		}
		if (!headerConsumed) {
			int remainingHeaderBytes = HEADER.length - storage.size();

			if (length >= remainingHeaderBytes) {
				boolean isHeader = isHeader(bytes.array(), start);
				if (!isHeader) {
					throw new IllegalStateException("Invalid Header");
				}
				headerConsumed = true;
				storage.addBytes(bytes.array(), start, remainingHeaderBytes);
				consume(bytes, start + remainingHeaderBytes, length - remainingHeaderBytes);
			}
			else {
				storage.addBytes(bytes.array(), start, length);
				consume(bytes, start + length, length);
			}
		}
		else if (contentLength == null) {
			int remainingContentLengthBytes = CONTENT_LENGTH_BYTES - (storage.size() - HEADER.length);

			if (length >= remainingContentLengthBytes) {
				contentLength = bytes.getInt(start);
				storage.addBytes(bytes.array(), start, remainingContentLengthBytes);
				consume(bytes, start + remainingContentLengthBytes, length - remainingContentLengthBytes);
			}
			else {
				storage.addBytes(bytes.array(), start, length);
				consume(bytes, start + length, length);
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
				consume(bytes, start + remainingContentBytes, length - remainingContentBytes);
			}
			else {
				storage.addBytes(bytes.array(), start, length);
				consume(bytes, start + length, length);
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
