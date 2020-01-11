package com.gitlab.tixtix320.sonder.internal.common.communication;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.ReadableByteChannel;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;

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

	private static final int CONTENT_LENGTH_BYTES = 8;

	private static final ScheduledExecutorService TIMEOUT_SCHEDULER = Executors.newScheduledThreadPool(3,
			PackChannel::daemonThread);

	private final ByteChannel channel;

	private final ByteBuffer protocolHeaderBuffer;

	private final ByteBuffer headersLengthBuffer;

	private final ByteBuffer contentLengthBuffer;

	private final Duration headersTimeoutDuration;

	private final LongFunction<Duration> contentTimeoutDurationFactory;

	private ScheduledFuture<?> headersTimeout;

	private ByteBuffer headersBuffer;

	private final Subject<Pack> packs;

	private State state;

	private long contentLength;

	public PackChannel(ByteChannel channel) {
		this(channel, Duration.ofSeconds(5), contentLength -> {
			long timout = Math.max((long) Math.ceil(contentLength * (60D / 1024 / 1024 / 1024)), 1);
			return Duration.ofSeconds(timout);
		});
	}

	public PackChannel(ByteChannel channel, Duration headersTimeoutDuration,
					   LongFunction<Duration> contentTimeoutDurationFactory) {
		this.channel = channel;
		this.protocolHeaderBuffer = ByteBuffer.allocate(PROTOCOL_HEADER_BYTES.length);
		this.headersLengthBuffer = ByteBuffer.allocate(HEADERS_LENGTH_BYTES);
		this.contentLengthBuffer = ByteBuffer.allocate(CONTENT_LENGTH_BYTES);
		this.headersTimeoutDuration = headersTimeoutDuration;
		this.contentTimeoutDurationFactory = contentTimeoutDurationFactory;
		this.headersBuffer = null;
		this.packs = Subject.single();
		this.state = State.PROTOCOL_HEADER;
		this.contentLength = 0;
	}

	public void write(Pack pack)
			throws IOException {
		writeHeaders(pack.getHeaders(), pack.getContentLength());
		writeContent(pack.channel(), pack.getContentLength());
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
					headersTimeout = scheduleHeadersTimeout();
					channel.read(protocolHeaderBuffer);

					if (protocolHeaderBuffer.hasRemaining()) {
						break cycle;
					}

					boolean isHeader = isHeader(protocolHeaderBuffer.array());
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
					contentLength = contentLengthBuffer.getLong();
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

					headersTimeout.cancel(false);
					byte[] headers = headersBuffer.array();

					state = state.next();
					constructPack(headers, contentLength);
					break cycle;
				default:
					throw new IllegalStateException(state.name());
			}

		}
	}

	private void constructPack(byte[] headers, long contentLength) {
		ReadableByteChannel channel;
		if (contentLength == 0) {
			channel = EmptyReadableByteChannel.SELF;
			reset();
		}
		else {
			LimitedReadableByteChannel limitedChannel = new LimitedReadableByteChannel(this.channel, contentLength);
			ScheduledFuture<?> contentTimeout = scheduleContentTimeout(limitedChannel);
			limitedChannel.onFinish().subscribe(none -> {
				contentTimeout.cancel(false);
				reset();
			});


			channel = limitedChannel;
		}

		Pack pack = new Pack(headers, channel, contentLength);
		packs.next(pack);
	}

	private ScheduledFuture<?> scheduleHeadersTimeout() {
		return TIMEOUT_SCHEDULER.schedule(() -> {
			reset();
			throw new TimeoutException("Headers not received long time.");
		}, headersTimeoutDuration.getSeconds(), TimeUnit.SECONDS);
	}

	private ScheduledFuture<?> scheduleContentTimeout(LimitedReadableByteChannel channel) {
		return TIMEOUT_SCHEDULER.schedule(() -> {
			long remaining = channel.getRemaining();
			channel.close();
			reset();

			throw new TimeoutException(
					String.format("The provided channel is not was fully read long time. %s bytes left", remaining));
		}, contentTimeoutDurationFactory.apply(contentLength).getSeconds(), TimeUnit.SECONDS);
	}

	private void reset() {
		state = State.first();
		protocolHeaderBuffer.clear();
		headersLengthBuffer.clear();
		contentLengthBuffer.clear();
		headersBuffer.clear();
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
