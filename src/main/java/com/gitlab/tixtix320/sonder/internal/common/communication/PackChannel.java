package com.gitlab.tixtix320.sonder.internal.common.communication;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.ReadableByteChannel;

import com.gitlab.tixtix320.kiwi.api.observable.Observable;
import com.gitlab.tixtix320.kiwi.api.observable.subject.Subject;
import com.gitlab.tixtix320.sonder.internal.common.util.EmptyReadableByteChannel;
import com.gitlab.tixtix320.sonder.internal.common.util.LimitedReadableByteChannel;

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

	private final ByteBuffer protocolHeaderBuffer;

	private final ByteBuffer headersLengthBuffer;

	private final ByteBuffer contentLengthBuffer;

	private ByteBuffer headersBuffer;

	private final Subject<Pack> packs;

	private State state;

	private int contentLength;

	public PackChannel(ByteChannel channel) {
		this.channel = channel;
		this.protocolHeaderBuffer = ByteBuffer.allocate(PROTOCOL_HEADER_BYTES.length);
		this.headersLengthBuffer = ByteBuffer.allocate(HEADERS_LENGTH_BYTES);
		this.contentLengthBuffer = ByteBuffer.allocate(CONTENT_LENGTH_BYTES);
		this.headersBuffer = ByteBuffer.allocate(0);
		this.packs = Subject.single();
		this.state = State.PROTOCOL_HEADER;
		this.contentLength = 0;
	}

	public void write(Pack pack)
			throws IOException {
		writeHeaders(pack.getHeaders(), pack.getContentLength());
		writeContent(pack.channel(), pack.getContentLength());
	}

	private void writeHeaders(byte[] headers, int contentLength)
			throws IOException {
		int headersLength = headers.length;

		ByteBuffer headersWriteBuffer = ByteBuffer.allocate(
				PROTOCOL_HEADER_BYTES.length + HEADERS_LENGTH_BYTES + CONTENT_LENGTH_BYTES + headersLength);

		headersWriteBuffer.put(PROTOCOL_HEADER_BYTES, 0, PROTOCOL_HEADER_BYTES.length);
		headersWriteBuffer.putInt(headersLength);
		headersWriteBuffer.putInt(contentLength);
		headersWriteBuffer.put(headers, 0, headersLength);
		headersWriteBuffer.flip();

		while (headersWriteBuffer.hasRemaining()) {
			channel.write(headersWriteBuffer);
		}
	}

	private void writeContent(ReadableByteChannel contentChannel, int contentLength)
			throws IOException {
		int capacity = 1024 * 64;
		ByteBuffer contentWriteBuffer = ByteBuffer.allocate(capacity);

		int remainingBytes = contentLength;

		while (remainingBytes != 0) {
			contentWriteBuffer.clear();
			int limit = Math.min(capacity, remainingBytes);
			contentWriteBuffer.limit(limit);
			int count = contentChannel.read(contentWriteBuffer);
			if (count < 0) {
				throw new InvalidPackException(
						String.format("Content channel ended, but but still remaining %s bytes", remainingBytes));
			}
			remainingBytes -= count;
			contentWriteBuffer.position(0);
			while (contentWriteBuffer.hasRemaining()) {
				this.channel.write(contentWriteBuffer);
			}
		}
	}

	/**
	 * @throws IOException          If any I/O error occurs
	 * @throws InvalidPackException If invalid pack is consumed
	 */
	public void read()
			throws IOException, InvalidPackException {
		if (state == State.last()) {
			return;
		}

		try {
			consume();
		}
		catch (IOException | InvalidPackException e) {
			reset();
			throw e;
		}
		catch (Throwable e) {
			reset();
			throw new IllegalStateException("Error occurs while consuming pack", e);
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
		packs.complete();
		channel.close();
	}

	private void consume()
			throws IOException, InvalidPackException {
		cycle:
		while (true) {
			switch (state) {
				case PROTOCOL_HEADER:
					channel.read(protocolHeaderBuffer);

					if (protocolHeaderBuffer.hasRemaining()) {
						break cycle;
					}

					boolean isHeader = isHeader(protocolHeaderBuffer.array(), 0);
					if (!isHeader) {
						throw new InvalidPackException("Invalid protocol header");
					}

					state = state.next();
					break;
				case HEADERS_LENGTH:
					channel.read(headersLengthBuffer);

					if (headersLengthBuffer.hasRemaining()) {
						break cycle;
					}

					headersLengthBuffer.flip();
					int headersLength = headersLengthBuffer.getInt();
					if (headersLength <= 0) {
						throw new InvalidPackException(String.format("Invalid headers length: %s", headersLength));
					}

					state = state.next();
					headersBuffer = ByteBuffer.allocate(headersLength);
					break;
				case CONTENT_LENGTH:
					channel.read(contentLengthBuffer);

					if (contentLengthBuffer.hasRemaining()) {
						break cycle;
					}

					contentLengthBuffer.flip();
					contentLength = contentLengthBuffer.getInt();
					if (contentLength < 0) {
						throw new InvalidPackException(String.format("Invalid content length: %s", contentLength));
					}

					state = state.next();
					break;
				case HEADERS:
					channel.read(headersBuffer);

					if (headersBuffer.hasRemaining()) {
						break cycle;
					}

					byte[] headers = headersBuffer.array();

					state = state.next();
					constructPack(headers, contentLength);
					break cycle;
				default:
					throw new IllegalStateException(state.name());
			}

		}
	}

	private void reset() {
		state = State.first();
		protocolHeaderBuffer.clear();
		headersLengthBuffer.clear();
		contentLengthBuffer.clear();
		headersBuffer.clear();
	}

	private void constructPack(byte[] headers, int contentLength) {
		ReadableByteChannel channel;
		if (contentLength == 0) {
			channel = EmptyReadableByteChannel.SELF;
			reset();
		}
		else {
			LimitedReadableByteChannel limitedChannel = new LimitedReadableByteChannel(this.channel, contentLength);
			limitedChannel.onFinish().subscribe(none -> reset());
			channel = limitedChannel;
		}

		Pack pack = new Pack(headers, channel, contentLength);
		packs.next(pack);
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
				throw new IllegalStateException();
			}
		};

		public abstract State next();

		public static State first() {
			return PROTOCOL_HEADER;
		}

		public static State last() {
			return CONTENT;
		}
	}
}
