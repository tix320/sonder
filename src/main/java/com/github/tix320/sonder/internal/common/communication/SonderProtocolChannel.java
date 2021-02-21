package com.github.tix320.sonder.internal.common.communication;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.Channel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.kiwi.api.reactive.publisher.Publisher;
import com.github.tix320.sonder.api.common.communication.CertainReadableByteChannel;
import com.github.tix320.sonder.api.common.communication.EmptyReadableByteChannel;

public final class SonderProtocolChannel implements Channel {

	private static final byte[] PROTOCOL_HEADER_BYTES = {
			105, 99, 111, 110, 116, 114, 111, 108};

	private static final int HEADERS_LENGTH_BYTES = 4;

	private static final int CONTENT_LENGTH_BYTES = 8;

	private final ByteChannel channel;

	private volatile ReadMetaData lastReadMetaData;

	private final ByteBuffer protocolHeaderBuffer = ByteBuffer.allocateDirect(PROTOCOL_HEADER_BYTES.length);

	private final ByteBuffer headersLengthBuffer = ByteBuffer.allocateDirect(HEADERS_LENGTH_BYTES);

	private final ByteBuffer contentLengthBuffer = ByteBuffer.allocateDirect(CONTENT_LENGTH_BYTES);

	private final Queue<Pack> writeQueue;

	private volatile WriteMetaData lastWriteMetaData;

	private final ByteBuffer writeContentBuffer = ByteBuffer.allocateDirect(1024 * 64);

	{
		resetWriteContentBuffer();
	}

	private final Object readLock = new Object();
	private final Object writeLock = new Object();

	public SonderProtocolChannel(ByteChannel channel) {
		this.channel = channel;
		this.writeQueue = new LinkedList<>();
		this.lastWriteMetaData = null;
		this.lastReadMetaData = null;
	}

	public boolean write(Pack pack) throws IOException {
		synchronized (writeLock) {
			try {
				if (lastWriteMetaData != null) {
					writeQueue.add(pack);
					return false;
				} else {
					WriteMetaData lastWriteMetaData = writePack(pack);
					changeLastWriteMetaDataTo(lastWriteMetaData);
					return lastWriteMetaData == null;
				}
			} catch (IOException e) {
				close();
				throw e;
			} catch (Throwable e) {
				close();
				throw new IllegalStateException("Error occurs while writing pack", e);
			}
		}
	}

	public boolean writeLastPack() throws IOException {
		synchronized (writeLock) {
			try {
				if (lastWriteMetaData == null) {
					return true;
				} else {
					WriteMetaData writeMetaData = writeFromMetadata(this.lastWriteMetaData);
					if (writeMetaData != null) {
						return false;
					} else {
						while (!writeQueue.isEmpty()) {
							Pack pack = writeQueue.poll();
							WriteMetaData metaData = writePack(pack);
							if (metaData != null) {
								changeLastWriteMetaDataTo(metaData);
								return false;
							}
						}

						changeLastWriteMetaDataTo(null);
						return true;
					}
				}
			} catch (IOException e) {
				close();
				throw e;
			} catch (Throwable e) {
				close();
				throw new IllegalStateException("Error occurs while writing pack", e);
			}
		}
	}


	/**
	 * @throws IOException                    If any I/O error occurs
	 * @throws SonderProtocolException        If invalid pack is consumed
	 * @throws PackNotReadyException          If pack not ready fully yet
	 * @throws ContentReadInProgressException If pack content read in progress
	 */
	public ReceivedPacket read() throws IOException, PackNotReadyException, ContentReadInProgressException {
		synchronized (readLock) {
			if (lastReadMetaData == null) {
				lastReadMetaData = new ReadMetaData();
			}
			try {
				return consume();
			} catch (IOException e) {
				close();
				throw e;
			} catch (PackNotReadyException | ContentReadInProgressException e) {

				throw e;
			} catch (Throwable e) {
				close();
				throw new IllegalStateException("Error occurs while consuming pack", e);
			}
		}
	}

	@Override
	public boolean isOpen() {
		return channel.isOpen();
	}

	@Override
	public void close() throws IOException {
		channel.close();
	}

	private WriteMetaData writePack(Pack pack) throws IOException {
		WriteMetaData writeMetaData = new WriteMetaData(pack);
		return writeFromMetadata(writeMetaData);
	}

	private WriteMetaData writeFromMetadata(WriteMetaData writeMetaData) throws IOException {
		CertainReadableByteChannel contentChannel = writeMetaData.getContentChannel();

		ByteBuffer headersBuffer = writeMetaData.getHeadersBuffer();

		while (headersBuffer.hasRemaining()) {
			int writeCount = this.channel.write(headersBuffer);
			if (writeCount == 0) {
				return writeMetaData;
			}
		}

		ByteBuffer contentWriteBuffer = writeMetaData.getContentBuffer();

		long remainingBytes = writeMetaData.getRemainingContentBytes();

		while (remainingBytes != 0) {
			if (contentWriteBuffer.hasRemaining()) {
				int writeCount = this.channel.write(contentWriteBuffer);
				if (writeCount == 0) {
					writeMetaData.changeRemainingContentBytes(remainingBytes);
					return writeMetaData;
				}
				remainingBytes -= writeCount;
			}

			if (contentWriteBuffer.hasRemaining()) {
				writeMetaData.changeRemainingContentBytes(remainingBytes);
				return writeMetaData;
			} else if (remainingBytes == 0) {
				return null;
			}

			contentWriteBuffer.position(0);

			int limit = (int) Math.min(contentWriteBuffer.capacity(), remainingBytes);
			contentWriteBuffer.limit(limit);
			int count = contentChannel.read(contentWriteBuffer);
			if (count < 0) {
				throw new SonderProtocolException(
						String.format("Content channel ended, but still remaining %s bytes", remainingBytes));
			}
			contentWriteBuffer.flip();
		}

		return null;
	}

	private void changeLastWriteMetaDataTo(WriteMetaData writeMetaData) {
		resetWriteContentBuffer();
		this.lastWriteMetaData = writeMetaData;
	}

	private ReceivedPacket consume() throws IOException, PackNotReadyException, ContentReadInProgressException {
		synchronized (readLock) {
			while (true) {
				ReadMetaData readMetaData = this.lastReadMetaData;
				switch (readMetaData.state) {
					case PROTOCOL_HEADER:
						ByteBuffer protocolHeaderBuffer = readMetaData.getProtocolHeaderBuffer();
						readToBuffer(protocolHeaderBuffer);

						if (protocolHeaderBuffer.hasRemaining()) {
							throw new PackNotReadyException();
						}

						protocolHeaderBuffer.flip();
						checkHeader();

						readMetaData.toNextState();
						break;
					case HEADERS_LENGTH:
						ByteBuffer headersLengthBuffer = readMetaData.getHeadersLengthBuffer();
						readToBuffer(headersLengthBuffer);

						if (headersLengthBuffer.hasRemaining()) {
							throw new PackNotReadyException();
						}

						headersLengthBuffer.flip();
						int headersLength = headersLengthBuffer.getInt();
						if (headersLength <= 0) {
							throw new SonderProtocolException(
									String.format("Invalid headers length: %s", headersLength));
						}

						readMetaData.toNextState();
						readMetaData.allocateHeadersBuffer(headersLength);
						break;
					case CONTENT_LENGTH:
						ByteBuffer contentLengthBuffer = readMetaData.getContentLengthBuffer();
						readToBuffer(contentLengthBuffer);

						if (contentLengthBuffer.hasRemaining()) {
							throw new PackNotReadyException();
						}

						contentLengthBuffer.flip();
						long contentLength = contentLengthBuffer.getLong();
						readMetaData.setContentLength(contentLength);
						if (contentLength < 0) {
							throw new SonderProtocolException(
									String.format("Invalid content length: %s", contentLength));
						}

						readMetaData.toNextState();
						break;
					case HEADERS:
						ByteBuffer buffer = readMetaData.headersBuffer;
						readToBuffer(buffer);

						if (buffer.hasRemaining()) {
							throw new PackNotReadyException();
						}

						byte[] headers = buffer.array();

						readMetaData.toNextState();
						return constructPack(headers, readMetaData.contentLength);
					case CONTENT:
						readMetaData.contentChannel.notifyForAvailability();
						throw new ContentReadInProgressException();
					default:
						throw new IllegalStateException(readMetaData.state.name());
				}
			}
		}
	}

	private ReceivedPacket constructPack(byte[] headers, long contentLength) {
		if (contentLength == 0) {
			resetRead();

			CertainReadableByteChannel channel = EmptyReadableByteChannel.SELF;
			Pack pack = new Pack(headers, channel);
			return new ReceivedPacket() {
				@Override
				public Pack getPack() {
					return pack;
				}

				@Override
				public Observable<PacketState> state() {
					return Observable.of(PacketState.COMPLETED);
				}
			};
		} else {
			Publisher<PacketState> packetStatePublisher = Publisher.simple();

			BlockingCertainReadableByteChannel contentChannel = new BlockingCertainReadableByteChannel(this.channel,
					contentLength);

			lastReadMetaData.setContentChannel(contentChannel);

			contentChannel.emptiness().subscribe(none -> packetStatePublisher.publish(PacketState.EMPTY));

			contentChannel.completeness().subscribe(none -> {
				synchronized (readLock) {
					contentChannel.close();
					packetStatePublisher.publish(PacketState.COMPLETED);
					packetStatePublisher.complete();
					resetRead();
				}
			});

			Pack pack = new Pack(headers, contentChannel);

			return new ReceivedPacket() {
				@Override
				public Pack getPack() {
					return pack;
				}

				@Override
				public Observable<PacketState> state() {
					return packetStatePublisher.asObservable();
				}
			};
		}
	}

	private void resetRead() {
		synchronized (readLock) {
			lastReadMetaData.reset();
			lastReadMetaData = null;
		}
	}

	private void resetWriteContentBuffer() {
		writeContentBuffer.position(0);
		writeContentBuffer.limit(0);
	}

	private void readToBuffer(ByteBuffer byteBuffer) throws IOException {
		int read = this.channel.read(byteBuffer);
		if (read == -1) {
			close();
			throw new ClosedChannelException();
		}
	}

	private void checkHeader() throws SonderProtocolException {
		ByteBuffer buffer = lastReadMetaData.getProtocolHeaderBuffer();
		int end = PROTOCOL_HEADER_BYTES.length;
		int index = 0;
		for (int i = 0; i < end; i++) {
			if (buffer.get() != PROTOCOL_HEADER_BYTES[index++]) {
				buffer.rewind();
				throw new SonderProtocolException("Invalid protocol header");
			}
		}

		buffer.rewind();
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
	}

	private final class ReadMetaData {

		private volatile State state;
		private volatile ByteBuffer headersBuffer;
		private volatile long contentLength;
		private volatile BlockingCertainReadableByteChannel contentChannel;

		public ReadMetaData() {
			this.state = State.PROTOCOL_HEADER;
			this.headersBuffer = null;
			this.contentLength = -1;
			this.contentChannel = null;
		}

		public void toNextState() {
			this.state = state.next();
		}

		public void allocateHeadersBuffer(int headersLength) {
			this.headersBuffer = ByteBuffer.allocate(headersLength);
		}

		public void setContentLength(long contentLength) {
			this.contentLength = contentLength;
		}

		public void setContentChannel(BlockingCertainReadableByteChannel channel) {
			this.contentChannel = channel;
		}

		public ByteBuffer getProtocolHeaderBuffer() {
			return protocolHeaderBuffer;
		}

		public ByteBuffer getHeadersLengthBuffer() {
			return headersLengthBuffer;
		}

		public ByteBuffer getContentLengthBuffer() {
			return contentLengthBuffer;
		}

		public void reset() {
			this.state = State.PROTOCOL_HEADER;
			this.headersBuffer = null;
			this.contentLength = -1;
			this.contentChannel = null;
			protocolHeaderBuffer.clear();
			headersLengthBuffer.clear();
			contentLengthBuffer.clear();
		}
	}

	private final class WriteMetaData {

		private final CertainReadableByteChannel contentChannel;
		private final ByteBuffer headersBuffer;
		private volatile long remainingContentBytes;

		public WriteMetaData(Pack pack) {
			byte[] headers = pack.getHeaders();
			CertainReadableByteChannel contentChannel = pack.channel();
			long contentLength = contentChannel.getContentLength();
			ByteBuffer headersBuffer = fillHeadersBuffer(headers, contentLength);
			this.contentChannel = contentChannel;
			this.headersBuffer = headersBuffer;
			this.remainingContentBytes = contentLength;
		}

		public ByteBuffer getContentBuffer() {
			return writeContentBuffer;
		}

		public CertainReadableByteChannel getContentChannel() {
			return contentChannel;
		}

		public ByteBuffer getHeadersBuffer() {
			return headersBuffer;
		}

		public long getRemainingContentBytes() {
			return remainingContentBytes;
		}

		public void changeRemainingContentBytes(long value) {
			if (value < 0) {
				throw new IllegalArgumentException("Negative value: " + value);
			}
			remainingContentBytes = value;
		}

		private ByteBuffer fillHeadersBuffer(byte[] headers, long contentLength) {
			int headersLength = headers.length;

			ByteBuffer buffer = ByteBuffer.allocate(
					PROTOCOL_HEADER_BYTES.length + HEADERS_LENGTH_BYTES + CONTENT_LENGTH_BYTES + headersLength);

			buffer.put(PROTOCOL_HEADER_BYTES, 0, PROTOCOL_HEADER_BYTES.length);
			buffer.putInt(headersLength);
			buffer.putLong(contentLength);
			buffer.put(headers, 0, headersLength);
			buffer.flip();

			return buffer;
		}
	}

	public static final class PackNotReadyException extends Exception {

		public PackNotReadyException() {
		}
	}

	public static final class ContentReadInProgressException extends Exception {

		public ContentReadInProgressException() {
		}
	}

	public interface ReceivedPacket {

		Pack getPack();

		Observable<PacketState> state();
	}

	public enum PacketState {
		EMPTY,
		COMPLETED
	}
}
