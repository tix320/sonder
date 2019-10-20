package com.gitlab.tixtix320.sonder.internal.common.communication;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;

import com.gitlab.tixtix320.kiwi.api.observable.Observable;
import com.gitlab.tixtix320.kiwi.api.observable.subject.Subject;
import com.gitlab.tixtix320.sonder.internal.common.util.ByteArrayList;

public final class PackChannel implements Closeable {

	private static final byte[] HEADER = {105, 99, 111, 110, 116, 114, 111, 108};

	private static final int CONTENT_LENGTH_BYTES = 4;

	private final ByteChannel channel;

	private final ByteBuffer buffer;

	private final Subject<byte[]> packs;

	private final ByteArrayList storage;

	private boolean headerConsumed;

	private Integer contentLength;

	public PackChannel(ByteChannel channel) {
		this.channel = channel;
		this.buffer = ByteBuffer.allocate(1024);
		this.packs = Subject.single();
		this.storage = new ByteArrayList();
	}

	public void write(byte[] bytes) throws IOException {
		ByteBuffer buffer = ByteBuffer.allocate(HEADER.length + CONTENT_LENGTH_BYTES + bytes.length);
		buffer.put(HEADER, 0, HEADER.length);
		buffer.putInt(bytes.length);
		buffer.put(bytes, 0, bytes.length);
		buffer.flip();
		channel.write(buffer);
	}

	/**
	 * @throws IOException          If any I/O error occurs
	 * @throws PackConsumeException If error occurs in pack consuming
	 */
	public void read() throws IOException, PackConsumeException {
		buffer.position(0);
		int bytesCount;

		bytesCount = channel.read(buffer);
		try {
			consume(buffer, bytesCount);
		}
		catch (Throwable e) {
			throw new PackConsumeException("Error occurs while consuming pack", e);
		}
	}

	public Observable<byte[]> packs() {
		return packs.asObservable();
	}

	public boolean isOpen() {
		return channel.isOpen();
	}

	@Override
	public void close() throws IOException {
		buffer.clear();
		packs.complete();
		channel.close();
	}

	private void consume(ByteBuffer bytes, int length) {
		int start = 0;
		while (length != 0) {
			if (!headerConsumed) {
				int remainingHeaderBytes = HEADER.length - storage.size();

				if (length >= remainingHeaderBytes) {
					boolean isHeader = isHeader(bytes.array(), start);
					if (!isHeader) {
						throw new IllegalStateException("Invalid pack header");
					}
					headerConsumed = true;
					storage.addBytes(bytes.array(), start, remainingHeaderBytes);
					start = start + remainingHeaderBytes;
					length = length - remainingHeaderBytes;
				}
				else {
					storage.addBytes(bytes.array(), start, length);
					break;
				}
			}
			else if (contentLength == null) {
				int remainingContentLengthBytes = CONTENT_LENGTH_BYTES - (storage.size() - HEADER.length);

				if (length >= remainingContentLengthBytes) {
					contentLength = bytes.getInt(start);
					if (contentLength <= 0) {
						throw new IllegalStateException(String.format("Invalid content length: %s", contentLength));
					}
					storage.addBytes(bytes.array(), start, remainingContentLengthBytes);
					start = start + remainingContentLengthBytes;
					length = length - remainingContentLengthBytes;
				}
				else {
					storage.addBytes(bytes.array(), start, length);
					break;
				}
			}
			else {
				// read remaining content
				int remainingContentBytes = contentLength - (storage.size() - HEADER.length - CONTENT_LENGTH_BYTES);

				if (length >= remainingContentBytes) {
					storage.addBytes(bytes.array(), start, remainingContentBytes);
					byte[] data = storage.asArray();
					byte[] requestData = new byte[contentLength];
					System.arraycopy(data, HEADER.length + CONTENT_LENGTH_BYTES, requestData, 0, contentLength);
					packs.next(requestData);
					reset();
					start = start + remainingContentBytes;
					length = length - remainingContentBytes;
				}
				else {
					storage.addBytes(bytes.array(), start, length);
					break;
				}
			}
		}
	}

	private void reset() {
		storage.clear();
		headerConsumed = false;
		contentLength = null;
	}

	private static boolean isHeader(byte[] array, int start) {
		int end = start + HEADER.length;
		int index = 0;
		for (int i = start; i < end; i++) {
			if (array[i] != HEADER[index++]) {
				return false;
			}
		}
		return true;
	}
}
