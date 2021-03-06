package com.github.tix320.sonder.internal.common.communication;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.Channel;
import java.nio.channels.ClosedChannelException;
import java.util.LinkedList;
import java.util.Queue;

import com.github.tix320.sonder.api.common.communication.channel.FiniteReadableByteChannel;

public final class SonderProtocolChannel implements Channel {

	private static final byte[] PROTOCOL_HEADER_BYTES = {
			105, 99, 111, 110, 116, 114, 111, 108};

	private static final int HEADERS_LENGTH_BYTES = 4;

	private static final int CONTENT_LENGTH_BYTES = 8;

	private final ByteChannel sourceChannel;

	private volatile ReadMetaData currentReadMetaData;

	private final ByteBuffer protocolHeaderBuffer = ByteBuffer.allocateDirect(PROTOCOL_HEADER_BYTES.length);

	private final ByteBuffer headersLengthBuffer = ByteBuffer.allocateDirect(HEADERS_LENGTH_BYTES);

	private final ByteBuffer contentLengthBuffer = ByteBuffer.allocateDirect(CONTENT_LENGTH_BYTES);

	private final Queue<Pack> writeQueue;

	private volatile WriteMetaData currentWriteMetaData;

	private final ByteBuffer writeContentBuffer = ByteBuffer.allocateDirect(1024 * 64);

	private final Object readLock = new Object();
	private final Object writeLock = new Object();

	public SonderProtocolChannel(ByteChannel sourceChannel) {
		this.sourceChannel = sourceChannel;
		this.writeQueue = new LinkedList<>();
		this.currentWriteMetaData = null;
		this.currentReadMetaData = null;
	}

	public boolean tryWrite(Pack pack) throws IOException {
		synchronized (writeLock) {
			try {
				if (currentWriteMetaData != null) { // already other pack write in progress, just put in queue
					writeQueue.add(pack);
					return false;
				} else {
					WriteMetaData writeMetaData = new WriteMetaData(pack);
					boolean complete = writeFromMetadata(writeMetaData);
					if (!complete) {
						this.currentWriteMetaData = writeMetaData;
					}
					return complete;
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

	public boolean continueWriting() throws IOException {
		synchronized (writeLock) {
			try {
				if (currentWriteMetaData == null) {
					return true;
				} else {
					boolean complete = writeFromMetadata(this.currentWriteMetaData);
					if (!complete) {
						return false;
					} else {
						while (!writeQueue.isEmpty()) {
							Pack pack = writeQueue.poll();
							WriteMetaData writeMetaData = new WriteMetaData(pack);
							complete = writeFromMetadata(writeMetaData);
							if (!complete) {
								this.currentWriteMetaData = writeMetaData;
								return false;
							}
						}

						this.currentWriteMetaData = null;
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
	 * @throws IOException              If any I/O error occurs
	 * @throws SonderProtocolException  If invalid pack is consumed
	 * @throws PackNotReadyException    If pack not ready fully yet
	 * @throws PackAlreadyReadException If pack already read
	 */
	public ReceivedPack tryRead() throws IOException, PackNotReadyException, PackAlreadyReadException {
		synchronized (readLock) {
			if (currentReadMetaData == null) {
				currentReadMetaData = new ReadMetaData();
			}
			try {
				return consume();
			} catch (IOException e) {
				close();
				throw e;
			} catch (PackNotReadyException | PackAlreadyReadException e) {
				throw e;
			} catch (Throwable e) {
				close();
				throw new IllegalStateException("Error occurs while consuming pack", e);
			}
		}
	}

	public void resetReadState() {
		synchronized (readLock) {
			currentReadMetaData = null;
		}
	}

	public ByteChannel getSourceChannel() {
		return sourceChannel;
	}

	@Override
	public boolean isOpen() {
		return sourceChannel.isOpen();
	}

	@Override
	public void close() throws IOException {
		sourceChannel.close();
	}

	private boolean writeFromMetadata(WriteMetaData writeMetaData) throws IOException {
		FiniteReadableByteChannel contentChannel = writeMetaData.getContentChannel();

		ByteBuffer headersBuffer = writeMetaData.getHeadersBuffer();

		while (headersBuffer.hasRemaining()) {
			int writeCount = this.sourceChannel.write(headersBuffer);
			if (writeCount == 0) {
				return false;
			}
		}

		ByteBuffer contentWriteBuffer = writeMetaData.getContentBuffer();

		long remainingBytes = writeMetaData.getRemainingContentBytes();

		boolean completed;
		while (true) {
			if (contentWriteBuffer.hasRemaining()) {
				int writeCount = this.sourceChannel.write(contentWriteBuffer);
				if (writeCount == 0) {
					completed = false;
					break;
				}
				remainingBytes -= writeCount;

				if (remainingBytes == 0) {
					completed = true;
					break;
				}
			}

			if (contentWriteBuffer.hasRemaining()) {
				completed = false;
				break;
			}

			int limit = (int) Math.min(contentWriteBuffer.capacity(), remainingBytes);
			contentWriteBuffer.position(0);
			contentWriteBuffer.limit(limit);

			int count = contentChannel.read(contentWriteBuffer);
			if (count == 0) {
				try {
					//noinspection BusyWait
					Thread.sleep(200);
				} catch (InterruptedException e) {
					throw new IOException("Interrupted while read", e);
				}
			} else if (count == -1) {
				throw new IOException(
						String.format("Content channel ended, but still remaining %s bytes", remainingBytes));
			} else {
				contentWriteBuffer.flip();
			}
		}

		writeMetaData.changeRemainingContentBytes(remainingBytes);
		return completed;
	}

	private ReceivedPack consume() throws IOException, PackNotReadyException, PackAlreadyReadException {
		synchronized (readLock) {
			while (true) {
				ReadMetaData readMetaData = this.currentReadMetaData;
				switch (readMetaData.state) {
					case PROTOCOL_HEADER:
						boolean success = readProtocolHeaderPart(readMetaData.getProtocolHeaderBuffer());

						if (success) {
							readMetaData.changeToNextState();
						} else {
							throw new PackNotReadyException();
						}

						break;
					case HEADERS_LENGTH:
						Integer headersLength = readHeadersLengthPart(readMetaData.getHeadersLengthBuffer());

						if (headersLength == null) {
							throw new PackNotReadyException();
						} else {
							readMetaData.changeToNextState();
							readMetaData.allocateHeadersBuffer(headersLength);
						}

						break;
					case CONTENT_LENGTH:
						Long contentLength = readContentLengthPart(readMetaData.getContentLengthBuffer());

						if (contentLength == null) {
							throw new PackNotReadyException();
						} else {
							readMetaData.changeToNextState();
							readMetaData.setContentLength(contentLength);
						}

						break;
					case HEADERS:
						byte[] headers = readHeadersPart(readMetaData.headersBuffer);

						if (headers == null) {
							throw new PackNotReadyException();
						} else {
							readMetaData.changeToNextState();
							return new ReceivedPack(headers, readMetaData.contentLength);
						}

					case CONTENT:
						throw new PackAlreadyReadException();
					default:
						throw new IllegalStateException(readMetaData.state.name());
				}
			}
		}
	}

	private boolean readProtocolHeaderPart(ByteBuffer protocolHeaderBuffer) throws IOException {
		readToBuffer(protocolHeaderBuffer);

		if (protocolHeaderBuffer.hasRemaining()) {
			return false;
		}

		protocolHeaderBuffer.flip();
		checkHeader(protocolHeaderBuffer);

		return true;
	}

	private Integer readHeadersLengthPart(ByteBuffer headersLengthBuffer) throws IOException {
		readToBuffer(headersLengthBuffer);

		if (headersLengthBuffer.hasRemaining()) {
			return null;
		}

		headersLengthBuffer.flip();
		int headersLength = headersLengthBuffer.getInt();
		if (headersLength <= 0) {
			throw new SonderProtocolException(String.format("Invalid headers length: %s", headersLength));
		}

		return headersLength;
	}

	private Long readContentLengthPart(ByteBuffer contentLengthBuffer) throws IOException {
		readToBuffer(contentLengthBuffer);

		if (contentLengthBuffer.hasRemaining()) {
			return null;
		}

		contentLengthBuffer.flip();
		long contentLength = contentLengthBuffer.getLong();
		if (contentLength < 0) {
			throw new SonderProtocolException(String.format("Invalid content length: %s", contentLength));
		}

		return contentLength;
	}

	private byte[] readHeadersPart(ByteBuffer headersBuffer) throws IOException {
		readToBuffer(headersBuffer);

		if (headersBuffer.hasRemaining()) {
			return null;
		}

		return headersBuffer.array();
	}

	private void readToBuffer(ByteBuffer byteBuffer) throws IOException {
		int read = this.sourceChannel.read(byteBuffer);
		if (read == -1) {
			close();
			throw new ClosedChannelException();
		}
	}

	private void checkHeader(ByteBuffer buffer) throws SonderProtocolException {
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
	}

	private final class ReadMetaData {

		private volatile State state;
		private volatile ByteBuffer headersBuffer;
		private volatile long contentLength;

		public ReadMetaData() {
			this.state = State.PROTOCOL_HEADER;
			this.headersBuffer = null;
			this.contentLength = -1;
			protocolHeaderBuffer.clear();
			headersLengthBuffer.clear();
			contentLengthBuffer.clear();
		}

		public void changeToNextState() {
			this.state = state.next();
		}

		public void allocateHeadersBuffer(int headersLength) {
			this.headersBuffer = ByteBuffer.allocate(headersLength);
		}

		public void setContentLength(long contentLength) {
			this.contentLength = contentLength;
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
	}

	private final class WriteMetaData {

		private final FiniteReadableByteChannel contentChannel;
		private final ByteBuffer headersBuffer;
		private volatile long remainingContentBytes;

		public WriteMetaData(Pack pack) {
			byte[] headers = pack.getHeaders();
			FiniteReadableByteChannel contentChannel = pack.contentChannel();
			long contentLength = contentChannel.getContentLength();
			ByteBuffer headersBuffer = fillHeadersBuffer(headers, contentLength);
			this.contentChannel = contentChannel;
			this.headersBuffer = headersBuffer;
			this.remainingContentBytes = contentLength;
			writeContentBuffer.position(0);
			writeContentBuffer.limit(0);
		}

		public ByteBuffer getContentBuffer() {
			return writeContentBuffer;
		}

		public FiniteReadableByteChannel getContentChannel() {
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

	public static final class PackNotReadyException extends Exception {}

	public static final class PackAlreadyReadException extends Exception {

		public PackAlreadyReadException() {
			super("Channel in content reading state. You must reset for future packs.");
		}
	}

	public static final class ReceivedPack {

		private final byte[] headers;

		private final long contentLength;

		public ReceivedPack(byte[] headers, long contentLength) {
			this.headers = headers;
			this.contentLength = contentLength;
		}

		public byte[] getHeaders() {
			return headers;
		}

		public long getContentLength() {
			return contentLength;
		}
	}
}
