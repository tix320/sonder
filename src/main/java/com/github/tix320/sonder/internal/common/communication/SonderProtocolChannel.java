package com.github.tix320.sonder.internal.common.communication;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.Channel;
import java.nio.channels.ClosedChannelException;
import java.util.LinkedList;
import java.util.Queue;

import com.github.tix320.sonder.api.common.communication.CertainReadableByteChannel;
import com.github.tix320.sonder.api.common.communication.EmptyReadableByteChannel;
import com.github.tix320.sonder.api.common.communication.LimitedReadableByteChannel;

public final class SonderProtocolChannel implements Channel {

	private static final byte[] PROTOCOL_HEADER_BYTES = {
			105, 99, 111, 110, 116, 114, 111, 108};

	private static final int HEADERS_LENGTH_BYTES = 4;

	private static final int CONTENT_LENGTH_BYTES = 8;

	private final ByteChannel channel;

	private final ByteBuffer protocolHeaderBuffer;

	private final ByteBuffer headersLengthBuffer;

	private final ByteBuffer contentLengthBuffer;

	private State state;

	private ByteBuffer headersBuffer;

	private long lastReadContentLength;

	private final Queue<Pack> writeQueue;

	private WriteMetaData lastWriteMetaData;

	private final ByteBuffer writeContentBuffer = ByteBuffer.allocateDirect(1024 * 64);

	{
		resetWriteContentBuffer();
	}

	private final Object readLock = new Object();
	private final Object writeLock = new Object();

	public SonderProtocolChannel(ByteChannel channel) {
		this.channel = channel;
		this.protocolHeaderBuffer = ByteBuffer.allocateDirect(PROTOCOL_HEADER_BYTES.length);
		this.headersLengthBuffer = ByteBuffer.allocateDirect(HEADERS_LENGTH_BYTES);
		this.contentLengthBuffer = ByteBuffer.allocateDirect(CONTENT_LENGTH_BYTES);
		this.headersBuffer = null;
		this.lastReadContentLength = 0;
		this.writeQueue = new LinkedList<>();
		this.lastWriteMetaData = null;
		this.state =State.PROTOCOL_HEADER;
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
	 * @throws IOException          If any I/O error occurs
	 * @throws InvalidPackException If invalid pack is consumed
	 */
	public Pack read() throws IOException, InvalidPackException {
		if (state == State.CONTENT) {
			return null;
		}
		synchronized (readLock) {
			try {
				return consume();
			} catch (InvalidPackException e) {
				resetRead();
				e.printStackTrace();
				return null;
			} catch (IOException e) {
				close();
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
				throw new InvalidPackException(
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

	private Pack consume() throws IOException {
		cycle:
		while (true) {
			State state = this.state;
			switch (state) {
				case PROTOCOL_HEADER:
					readToBuffer(protocolHeaderBuffer);

					if (protocolHeaderBuffer.hasRemaining()) {
						break cycle;
					}

					protocolHeaderBuffer.flip();
					checkHeader();

					this.state = this.state.next();
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

					this.state = this.state.next();
					headersBuffer = ByteBuffer.allocate(headersLength);
					break;
				case CONTENT_LENGTH:
					readToBuffer(contentLengthBuffer);

					if (contentLengthBuffer.hasRemaining()) {
						break cycle;
					}

					contentLengthBuffer.flip();
					long contentLength = contentLengthBuffer.getLong();
					this.lastReadContentLength = contentLength;
					if (contentLength < 0) {
						throw new InvalidPackException(String.format("Invalid content length: %s", contentLength));
					}

					this.state = this.state.next();
					break;
				case HEADERS:
					ByteBuffer buffer = headersBuffer;
					readToBuffer(buffer);

					if (buffer.hasRemaining()) {
						break cycle;
					}

					byte[] headers = buffer.array();

					this.state = this.state.next();
					return constructPack(headers, this.lastReadContentLength);
				default:
					throw new IllegalStateException(state.name());
			}
		}

		return null;
	}

	private Pack constructPack(byte[] headers, long contentLength) {
		if (contentLength == 0) {
			resetRead();

			CertainReadableByteChannel channel = EmptyReadableByteChannel.SELF;
			return new Pack(headers, channel);
		} else {
			LimitedReadableByteChannel limitedChannel = new LimitedReadableByteChannel(this.channel, contentLength);

			limitedChannel.completeness().subscribe(none -> {
				limitedChannel.close();
				synchronized (readLock) {
					resetRead();
				}
			});


			return new Pack(headers, limitedChannel);
		}
	}

	private void resetRead() {
		state = State.first();
		protocolHeaderBuffer.clear();
		headersLengthBuffer.clear();
		contentLengthBuffer.clear();
		headersBuffer = null;
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

	private void checkHeader() throws InvalidPackException {
		ByteBuffer buffer = this.protocolHeaderBuffer;
		int end = PROTOCOL_HEADER_BYTES.length;
		int index = 0;
		for (int i = 0; i < end; i++) {
			if (buffer.get() != PROTOCOL_HEADER_BYTES[index++]) {
				buffer.rewind();
				throw new InvalidPackException("Invalid protocol header");
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
}