package com.gitlab.tixtix320.sonder.internal.common.communication;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;

import com.gitlab.tixtix320.kiwi.api.observable.Observable;
import com.gitlab.tixtix320.kiwi.api.observable.subject.Subject;
import com.gitlab.tixtix320.sonder.internal.common.util.ByteArrayList;

public final class PackChannel implements Closeable {

	private static final byte[] PROTOCOL_HEADER_BYTES = {
			105,
			99,
			111,
			110,
			116,
			114,
			111,
			108
	};

	private static final int HEADERS_LENGTH_BYTES = 4;

	private static final int CONTENT_LENGTH_BYTES = 4;

	private final ByteChannel channel;

	private final ByteBuffer buffer;

	private final Subject<Pack> packs;

	private final ByteArrayList storage;

	private Integer headersLength;

	private Integer contentLength;

	private State state;

	private int remainingBytes;

	public PackChannel(ByteChannel channel) {
		this.channel = channel;
		this.buffer = ByteBuffer.allocate(1024 * 1024);
		this.packs = Subject.single();
		this.storage = new ByteArrayList(1024 * 1024);
		this.state = State.PROTOCOL_HEADER;
		this.remainingBytes = PROTOCOL_HEADER_BYTES.length;
	}

	public void write(Pack pack)
			throws IOException {
		byte[] headersBytes = pack.getHeaders();
		byte[] contentBytes = pack.getContent();
		ByteBuffer buffer = ByteBuffer.allocate(PROTOCOL_HEADER_BYTES.length
												+ HEADERS_LENGTH_BYTES
												+ CONTENT_LENGTH_BYTES
												+ headersBytes.length
												+ contentBytes.length);
		buffer.put(PROTOCOL_HEADER_BYTES, 0, PROTOCOL_HEADER_BYTES.length);
		buffer.putInt(headersBytes.length);
		buffer.putInt(contentBytes.length);
		buffer.put(headersBytes, 0, headersBytes.length);
		buffer.put(contentBytes, 0, contentBytes.length);
		buffer.flip();

		while (buffer.hasRemaining()) {
			channel.write(buffer);
		}
	}

	/**
	 * @throws IOException          If any I/O error occurs
	 * @throws PackConsumeException If error occurs in pack consuming
	 */
	public void read()
			throws IOException, PackConsumeException {
		int bytesCount;

		buffer.position(0);
		bytesCount = channel.read(buffer);
		try {
			consume(buffer, bytesCount);
		}
		catch (Throwable e) {
			reset();
			throw new PackConsumeException("Error occurs while consuming pack", e);
		}
	}

	public Observable<Pack> packs() {
		return packs.asObservable();
	}

	public boolean isOpen() {
		return channel.isOpen();
	}

	@Override
	public void close()
			throws IOException {
		buffer.clear();
		packs.complete();
		channel.close();
	}

	private void consume(ByteBuffer buffer, int length) {
		byte[] bytes = buffer.array();
		int start = 0;
		cycle:
		while (true) {
			switch (state) {
				case PROTOCOL_HEADER:
					if (length >= remainingBytes) {
						boolean isHeader = isHeader(buffer.array(), start);
						if (!isHeader) {
							throw new IllegalStateException("Invalid protocol header");
						}
						storage.addBytes(buffer.array(), start, remainingBytes);
						start = start + remainingBytes;
						length = length - remainingBytes;

						state = state.next();
						remainingBytes = HEADERS_LENGTH_BYTES;
					}
					else {
						readAvailableBytesToStorage(bytes, start, length);
						break cycle;
					}
					break;
				case HEADERS_LENGTH:
					if (length >= remainingBytes) {
						headersLength = buffer.getInt(start);
						if (headersLength <= 0) {
							throw new IllegalStateException(String.format("Invalid headers length: %s", headersLength));
						}
						storage.addBytes(buffer.array(), start, remainingBytes);
						start = start + remainingBytes;
						length = length - remainingBytes;

						state = state.next();
						remainingBytes = CONTENT_LENGTH_BYTES;
					}
					else {
						readAvailableBytesToStorage(bytes, start, length);
						break cycle;
					}
					break;
				case CONTENT_LENGTH:
					if (length >= remainingBytes) {
						contentLength = buffer.getInt(start);
						if (contentLength < 0) {
							throw new IllegalStateException(String.format("Invalid content length: %s", contentLength));
						}
						storage.addBytes(buffer.array(), start, remainingBytes);
						start = start + remainingBytes;
						length = length - remainingBytes;

						state = state.next();
						remainingBytes = headersLength;
					}
					else {
						readAvailableBytesToStorage(bytes, start, length);
						break cycle;
					}
					break;
				case HEADERS:
					if (length >= remainingBytes) {
						storage.addBytes(buffer.array(), start, remainingBytes);
						start = start + remainingBytes;
						length = length - remainingBytes;

						state = state.next();
						remainingBytes = contentLength;
					}
					else {
						readAvailableBytesToStorage(bytes, start, length);
						break cycle;
					}
					break;
				case CONTENT:
					if (length >= remainingBytes) {
						storage.addBytes(buffer.array(), start, remainingBytes);
						start = start + remainingBytes;
						length = length - remainingBytes;

						state = state.next();
						remainingBytes = 0;
					}
					else {
						readAvailableBytesToStorage(bytes, start, length);
						break cycle;
					}
					break;
				case READY:
					next();
					reset();
					break;
				default:
					throw new IllegalStateException();
			}

		}
	}

	private void readAvailableBytesToStorage(byte[] bytes, int start, int length) {
		storage.addBytes(bytes, start, length);
		remainingBytes = remainingBytes - length;
	}

	private void reset() {
		storage.clear();
		state = State.PROTOCOL_HEADER;
		remainingBytes = PROTOCOL_HEADER_BYTES.length;
		headersLength = null;
		contentLength = null;
	}

	private void next() {
		byte[] data = storage.asArray();
		byte[] requestHeaders = new byte[headersLength];
		System.arraycopy(data, PROTOCOL_HEADER_BYTES.length + HEADERS_LENGTH_BYTES + CONTENT_LENGTH_BYTES,
				requestHeaders, 0, headersLength);
		byte[] requestContent = new byte[contentLength];
		System.arraycopy(data,
				PROTOCOL_HEADER_BYTES.length + HEADERS_LENGTH_BYTES + CONTENT_LENGTH_BYTES + headersLength,
				requestContent, 0, contentLength);
		packs.next(new Pack(requestHeaders, requestContent));
	}

	private static boolean isHeader(byte[] array, int start) {
		int end = start + PROTOCOL_HEADER_BYTES.length;
		int index = 0;
		for (int i = start; i < end; i++) {
			if (array[i] != PROTOCOL_HEADER_BYTES[index++]) {
				return false;
			}
		}
		return true;
	}

	private enum State {
		PROTOCOL_HEADER {
			@Override
			public State next() {
				return HEADERS_LENGTH;
			}
		},
		HEADERS_LENGTH {
			@Override
			public State next() {
				return CONTENT_LENGTH;
			}
		},
		CONTENT_LENGTH {
			@Override
			public State next() {
				return HEADERS;
			}
		},
		HEADERS {
			@Override
			public State next() {
				return CONTENT;
			}
		},
		CONTENT {
			@Override
			public State next() {
				return READY;
			}
		},
		READY {
			@Override
			public State next() {
				return PROTOCOL_HEADER;
			}
		};

		public abstract State next();
	}
}
