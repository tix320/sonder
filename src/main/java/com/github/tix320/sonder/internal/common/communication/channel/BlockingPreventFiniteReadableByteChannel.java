package com.github.tix320.sonder.internal.common.communication.channel;

import java.io.IOException;
import java.nio.ByteBuffer;

import com.github.tix320.kiwi.observable.MonoObservable;
import com.github.tix320.skimp.api.object.None;
import com.github.tix320.sonder.api.common.communication.channel.FiniteReadableByteChannel;

/**
 * @author : Tigran Sargsyan
 * @since : 07.03.2021
 **/
public final class BlockingPreventFiniteReadableByteChannel extends AbstractCloseableChannel
		implements FiniteReadableByteChannel {

	private final FiniteReadableByteChannel sourceChannel;

	public BlockingPreventFiniteReadableByteChannel(FiniteReadableByteChannel sourceChannel) {
		this.sourceChannel = sourceChannel;
	}

	@Override
	public int read(ByteBuffer dst) throws IOException {
		int read = sourceChannel.read(dst);
		if (read == 0) {
			throw new IOException("Non-blocking source channel prohibited.");
		}
		return read;
	}


	@Override
	public long getContentLength() {
		return sourceChannel.getContentLength();
	}

	@Override
	public long getRemaining() {
		return sourceChannel.getRemaining();
	}

	@Override
	public byte[] readAllBytes() throws IOException {
		return sourceChannel.readAllBytes();
	}

	@Override
	public MonoObservable<None> completeness() {
		return sourceChannel.completeness();
	}

	@Override
	protected void implCloseChannel() throws IOException {
		sourceChannel.close();
	}
}
