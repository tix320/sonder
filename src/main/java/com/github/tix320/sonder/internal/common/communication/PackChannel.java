package com.github.tix320.sonder.internal.common.communication;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongFunction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tix320.kiwi.api.check.Try;
import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.kiwi.api.reactive.publisher.Publisher;
import com.github.tix320.sonder.api.common.communication.CertainReadableByteChannel;
import com.github.tix320.sonder.api.common.communication.Headers;
import com.github.tix320.sonder.api.common.communication.LimitedReadableByteChannel;
import com.github.tix320.sonder.internal.common.util.Threads;

public final class PackChannel implements Closeable {

	private static final byte[] PROTOCOL_HEADER_BYTES = {
			105, 99, 111, 110, 116, 114, 111, 108};

	private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

	private static final int HEADERS_LENGTH_BYTES = 4;

	private static final int CONTENT_LENGTH_BYTES = 8;

	private final ByteChannel channel;

	private final ByteBuffer protocolHeaderBuffer;

	private final ByteBuffer headersLengthBuffer;

	private final ByteBuffer contentLengthBuffer;

	private final LongFunction<Duration> contentTimeoutDurationFactory;

	private final Publisher<Pack> packs;

	private final AtomicReference<State> state;

	private ByteBuffer headersBuffer;

	private long contentLength;

	private final Lock readLock = new ReentrantLock();
	private final Lock writeLock = new ReentrantLock();

	public PackChannel(ByteChannel channel, LongFunction<Duration> contentTimeoutDurationFactory) {
		this.channel = channel;
		this.protocolHeaderBuffer = ByteBuffer.allocateDirect(PROTOCOL_HEADER_BYTES.length);
		this.headersLengthBuffer = ByteBuffer.allocateDirect(HEADERS_LENGTH_BYTES);
		this.contentLengthBuffer = ByteBuffer.allocateDirect(CONTENT_LENGTH_BYTES);
		this.contentTimeoutDurationFactory = contentTimeoutDurationFactory;
		this.headersBuffer = null;
		this.packs = Publisher.simple();
		this.state = new AtomicReference<>(State.PROTOCOL_HEADER);
		this.contentLength = 0;
	}

	public void write(Pack pack) throws IOException {
		writeLock.lock();
		try {
			long contentLength = pack.channel().getContentLength();
			writeHeaders(pack.getHeaders(), contentLength);
			writeContent(pack.channel(), contentLength);
		}
		finally {
			writeLock.unlock();
		}
	}

	/**
	 * @throws IOException          If any I/O error occurs
	 * @throws InvalidPackException If invalid pack is consumed
	 */
	public void read() throws IOException, InvalidPackException {
		if (state.get() == State.last()) {
			return;
		}
		readLock.lock();
		try {
			consume();
		}
		catch (InvalidPackException e) {
			e.printStackTrace();
		}
		catch (IOException e) {
			close();
			throw e;
		}
		catch (Throwable e) {
			close();
			throw new IllegalStateException("Error occurs while consuming pack", e);
		}
		finally {
			readLock.unlock();
		}
	}

	public Observable<Pack> packs() {
		return packs.asObservable();
	}

	public boolean isOpen() {
		return channel.isOpen();
	}

	@Override
	public void close() throws IOException {
		packs.complete();
		channel.close();
	}


	private void writeHeaders(byte[] headers, long contentLength) throws IOException {
		int headersLength = headers.length;

		ByteBuffer headersWriteBuffer = ByteBuffer.allocate(
				PROTOCOL_HEADER_BYTES.length + HEADERS_LENGTH_BYTES + CONTENT_LENGTH_BYTES + headersLength);

		headersWriteBuffer.put(PROTOCOL_HEADER_BYTES, 0, PROTOCOL_HEADER_BYTES.length);
		headersWriteBuffer.putInt(headersLength);
		headersWriteBuffer.putLong(contentLength);
		headersWriteBuffer.put(headers, 0, headersLength);
		headersWriteBuffer.flip();

		while (headersWriteBuffer.hasRemaining()) {
			channel.write(headersWriteBuffer);
		}
	}

	private void writeContent(ReadableByteChannel contentChannel, long contentLength) throws IOException {
		int capacity = 1024 * 64;
		ByteBuffer contentWriteBuffer = ByteBuffer.allocate(capacity);

		long remainingBytes = contentLength;

		while (remainingBytes != 0) {
			contentWriteBuffer.clear();
			int limit = Math.min(capacity, (int) Math.min(remainingBytes, Integer.MAX_VALUE));
			contentWriteBuffer.limit(limit);
			int count = contentChannel.read(contentWriteBuffer);
			if (count < 0) {
				throw new InvalidPackException(
						String.format("Content channel ended, but still remaining %s bytes", remainingBytes));
			}
			remainingBytes -= count;
			contentWriteBuffer.position(0);
			while (contentWriteBuffer.hasRemaining()) {
				this.channel.write(contentWriteBuffer);
			}
		}
	}

	private void consume() throws IOException, InvalidPackException {
		cycle:
		while (true) {
			State state = this.state.get();
			switch (state) {
				case PROTOCOL_HEADER:
					readToBuffer(protocolHeaderBuffer);

					if (protocolHeaderBuffer.hasRemaining()) {
						break cycle;
					}

					protocolHeaderBuffer.flip();
					boolean isHeader = isHeader(protocolHeaderBuffer);
					if (!isHeader) {
						byte[] bytes = new byte[8];
						protocolHeaderBuffer.get(bytes);
						throw new InvalidPackException(
								String.format("Invalid protocol header %s", Arrays.toString(bytes)));
					}

					this.state.updateAndGet(State::next);
					break;
				case HEADERS_LENGTH:
					readToBuffer(headersLengthBuffer);

					if (headersLengthBuffer.hasRemaining()) {
						break cycle;
					}

					headersLengthBuffer.flip();
					int headersLength = headersLengthBuffer.getInt();
					if (headersLength <= 0) {
						throw new InvalidPackException(String.format("Invalid headers length: %s", headersLength));
					}

					this.state.updateAndGet(State::next);
					headersBuffer = ByteBuffer.allocate(headersLength);
					break;
				case CONTENT_LENGTH:
					readToBuffer(contentLengthBuffer);

					if (contentLengthBuffer.hasRemaining()) {
						break cycle;
					}

					contentLengthBuffer.flip();
					long contentLength = contentLengthBuffer.getLong();
					this.contentLength = contentLength;
					if (contentLength < 0) {
						throw new InvalidPackException(String.format("Invalid content length: %s", contentLength));
					}

					this.state.updateAndGet(State::next);
					break;
				case HEADERS:
					ByteBuffer buffer = headersBuffer;
					readToBuffer(buffer);

					if (buffer.hasRemaining()) {
						break cycle;
					}

					byte[] headers = buffer.array();

					this.state.updateAndGet(State::next);
					constructPack(headers, this.contentLength);
					break cycle;
				default:
					throw new IllegalStateException(state.name());
			}
		}
	}

	private void constructPack(byte[] headers, long contentLength) {
		if (contentLength == 0) {
			reset();

			CertainReadableByteChannel channel = EmptyReadableByteChannel.SELF;
			Pack pack = new Pack(headers, channel);
			Threads.runAsync(() -> packs.publish(pack));
		}
		else {
			LimitedReadableByteChannel limitedChannel = new LimitedReadableByteChannel(this.channel, contentLength);

			Duration timeoutDuration = contentTimeoutDurationFactory.apply(contentLength);

			Pack pack = new Pack(headers, limitedChannel);
			Threads.runAsync(() -> packs.publish(pack));

			try {
				limitedChannel.onFinish().get(timeoutDuration);
				reset();
			}
			catch (InterruptedException e) {
				throw new IllegalStateException(e);
			}
			catch (com.github.tix320.kiwi.api.reactive.observable.TimeoutException e) {
				limitedChannel.close();
				reset();
				long remaining = limitedChannel.getRemaining();
				if (remaining != 0) {
					String headersString = Try.supply(() -> JSON_MAPPER.readValue(headers, Headers.class).toString())
							.getOrElse("Unknown headers");
					new TimeoutException(String.format(
							"The provided channel is not was fully read long time: %sms. Content length is %s, left to read %s bytes.\nHeaders: %s",
							timeoutDuration.toMillis(), contentLength, remaining, headersString)).printStackTrace();
				}
			}
		}
	}

	private void reset() {
		state.set(State.first());
		protocolHeaderBuffer.clear();
		headersLengthBuffer.clear();
		contentLengthBuffer.clear();
		headersBuffer = null;
	}

	private void readToBuffer(ByteBuffer byteBuffer) throws IOException {
		int read = this.channel.read(byteBuffer);
		if (read == -1) {
			close();
			throw new ClosedChannelException();
		}
	}

	private static boolean isHeader(ByteBuffer buffer) {
		int end = PROTOCOL_HEADER_BYTES.length;
		int index = 0;
		for (int i = 0; i < end; i++) {
			if (buffer.get() != PROTOCOL_HEADER_BYTES[index++]) {
				buffer.flip();
				return false;
			}
		}

		buffer.flip();
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
