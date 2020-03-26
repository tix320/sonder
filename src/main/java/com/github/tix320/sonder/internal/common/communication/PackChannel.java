package com.github.tix320.sonder.internal.common.communication;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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

	private static final ScheduledExecutorService TIMEOUT_SCHEDULER = Executors.newScheduledThreadPool(3,
			PackChannel::daemonThread);

	private final ByteChannel channel;

	private final ByteBuffer protocolHeaderBuffer;

	private final ByteBuffer headersLengthBuffer;

	private final ByteBuffer contentLengthBuffer;

	private final Duration headersTimeoutDuration;

	private final LongFunction<Duration> contentTimeoutDurationFactory;

	private final AtomicReference<ScheduledFuture<?>> headersTimeout;

	private final AtomicReference<ByteBuffer> headersBuffer;

	private final Publisher<Pack> packs;

	private final AtomicReference<State> state;

	private final AtomicLong contentLength;

	private final AtomicReference<CertainReadableByteChannel> currentContentChannel;

	public PackChannel(ByteChannel channel, Duration headersTimeoutDuration,
					   LongFunction<Duration> contentTimeoutDurationFactory) {
		this.channel = channel;
		this.protocolHeaderBuffer = ByteBuffer.allocate(PROTOCOL_HEADER_BYTES.length);
		this.headersLengthBuffer = ByteBuffer.allocate(HEADERS_LENGTH_BYTES);
		this.contentLengthBuffer = ByteBuffer.allocate(CONTENT_LENGTH_BYTES);
		this.headersTimeoutDuration = headersTimeoutDuration;
		this.contentTimeoutDurationFactory = contentTimeoutDurationFactory;
		this.headersBuffer = new AtomicReference<>(null);
		this.packs = Publisher.simple();
		this.state = new AtomicReference<>(State.PROTOCOL_HEADER);
		this.headersTimeout = new AtomicReference<>(null);
		this.contentLength = new AtomicLong(0);
		this.currentContentChannel = new AtomicReference<>(null);
	}

	public void write(Pack pack)
			throws IOException {
		long contentLength = pack.channel().getContentLength();
		writeHeaders(pack.getHeaders(), contentLength);
		writeContent(pack.channel(), contentLength);
	}

	/**
	 * @throws IOException          If any I/O error occurs
	 * @throws InvalidPackException If invalid pack is consumed
	 */
	public synchronized void read()
			throws IOException, InvalidPackException {
		if (state.get() == State.last()) {
			return;
		}

		try {
			consume();
		}
		catch (Exception e) {
			resetWithContent();
			throw e;
		}
		catch (Throwable e) {
			close();
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


	private void writeHeaders(byte[] headers, long contentLength)
			throws IOException {
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

	private void writeContent(ReadableByteChannel contentChannel, long contentLength)
			throws IOException {
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

	private void consume()
			throws IOException, InvalidPackException {
		cycle:
		while (true) {
			State state = this.state.get();
			switch (state) {
				case PROTOCOL_HEADER:
					int read = channel.read(protocolHeaderBuffer);
					if (read == -1) {
						close();
						throw new ClosedChannelException();
					}
					headersTimeout.set(scheduleHeadersTimeout());

					if (protocolHeaderBuffer.hasRemaining()) {
						break cycle;
					}

					boolean isHeader = isHeader(protocolHeaderBuffer.array());
					if (!isHeader) {
						throw new InvalidPackException("Invalid protocol header");
					}

					this.state.updateAndGet(State::next);
					break;
				case HEADERS_LENGTH:
					read = channel.read(headersLengthBuffer);
					if (read == -1) {
						close();
						throw new ClosedChannelException();
					}

					if (headersLengthBuffer.hasRemaining()) {
						break cycle;
					}

					headersLengthBuffer.flip();
					int headersLength = headersLengthBuffer.getInt();
					if (headersLength <= 0) {
						throw new InvalidPackException(String.format("Invalid headers length: %s", headersLength));
					}

					this.state.updateAndGet(State::next);
					headersBuffer.set(ByteBuffer.allocate(headersLength));
					break;
				case CONTENT_LENGTH:
					read = channel.read(contentLengthBuffer);
					if (read == -1) {
						close();
						throw new ClosedChannelException();
					}

					if (contentLengthBuffer.hasRemaining()) {
						break cycle;
					}

					contentLengthBuffer.flip();
					long contentLength = contentLengthBuffer.getLong();
					this.contentLength.set(contentLength);
					if (contentLength < 0) {
						throw new InvalidPackException(String.format("Invalid content length: %s", contentLength));
					}

					this.state.updateAndGet(State::next);
					break;
				case HEADERS:
					ByteBuffer buffer = headersBuffer.get();
					read = channel.read(buffer);
					if (read == -1) {
						close();
						throw new ClosedChannelException();
					}

					if (buffer.hasRemaining()) {
						break cycle;
					}

					headersTimeout.get().cancel(false);
					byte[] headers = buffer.array();

					this.state.updateAndGet(State::next);
					constructPack(headers, this.contentLength.get());
					break cycle;
				default:
					throw new IllegalStateException(state.name());
			}

		}
	}

	private void constructPack(byte[] headers, long contentLength) {
		CertainReadableByteChannel channel;
		if (contentLength == 0) {
			channel = EmptyReadableByteChannel.SELF;
			reset();
		}
		else {
			LimitedReadableByteChannel limitedChannel = new LimitedReadableByteChannel(this.channel, contentLength);
			ScheduledFuture<?> contentTimeout = scheduleContentTimeout(headers, limitedChannel);
			limitedChannel.onFinish().subscribe(none -> {
				contentTimeout.cancel(false);
				reset();
			});


			channel = limitedChannel;
			currentContentChannel.set(limitedChannel);
		}

		Pack pack = new Pack(headers, channel);

		Threads.runAsync(() -> packs.publish(pack));
	}

	private ScheduledFuture<?> scheduleHeadersTimeout() {
		long delay = headersTimeoutDuration.toMillis();
		return TIMEOUT_SCHEDULER.schedule(() -> {
			reset();
			new TimeoutException(String.format("Headers not received long time: %sms", delay)).printStackTrace();
		}, delay, TimeUnit.MILLISECONDS);
	}

	private ScheduledFuture<?> scheduleContentTimeout(byte[] headers, LimitedReadableByteChannel channel) {
		long delay = contentTimeoutDurationFactory.apply(contentLength.get()).toMillis();
		return TIMEOUT_SCHEDULER.schedule(() -> {
			channel.close();
			reset();
			long remaining = channel.getRemaining();
			if (remaining != 0) {
				String headersString = Try.supply(() -> JSON_MAPPER.readValue(headers, Headers.class).toString())
						.getOrElse("Unknown headers");
				new TimeoutException(String.format(
						"The provided channel is not was fully read long time: %sms. Content length is %s, left to read %s bytes.\nHeaders: %s",
						delay, contentLength, remaining, headersString)).printStackTrace();
			}
		}, delay, TimeUnit.MILLISECONDS);
	}

	private synchronized void reset() {
		state.set(State.first());
		protocolHeaderBuffer.clear();
		headersLengthBuffer.clear();
		contentLengthBuffer.clear();
		headersBuffer.set(null);
	}

	private synchronized void resetWithContent() {
		reset();
		currentContentChannel.updateAndGet(channel -> {
			if (channel != null) {
				if (channel.getRemaining() > 0) {
					Try.runOrRethrow(channel::readRemainingInVain);
				}
			}
			return channel;
		});
	}

	private static boolean isHeader(byte[] array) {
		int end = PROTOCOL_HEADER_BYTES.length;
		int index = 0;
		for (int i = 0; i < end; i++) {
			if (array[i] != PROTOCOL_HEADER_BYTES[index++]) {
				return false;
			}
		}
		return true;
	}

	private static Thread daemonThread(Runnable runnable) {
		Thread thread = new Thread(runnable);
		thread.setDaemon(true);
		return thread;
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
