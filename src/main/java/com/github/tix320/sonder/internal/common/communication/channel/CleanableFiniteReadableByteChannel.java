package com.github.tix320.sonder.internal.common.communication.channel;

import java.io.IOException;
import java.nio.ByteBuffer;

import com.github.tix320.kiwi.api.reactive.observable.MonoObservable;
import com.github.tix320.skimp.api.object.None;
import com.github.tix320.sonder.api.common.communication.channel.FiniteReadableByteChannel;

/**
 * @author : Tigran Sargsyan
 * @since : 06.03.2021
 **/
public final class CleanableFiniteReadableByteChannel extends AbstractCloseableChannel
		implements FiniteReadableByteChannel {

	private final FiniteReadableByteChannel sourceChannel;

	public CleanableFiniteReadableByteChannel(FiniteReadableByteChannel sourceChannel) {
		this.sourceChannel = sourceChannel;
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
	public int read(ByteBuffer dst) throws IOException {
		return sourceChannel.read(dst);
	}

	@Override
	protected void implCloseChannel() throws IOException {
		readRemainingInVain();
		sourceChannel.close();
	}

	private void readRemainingInVain() throws IOException {
		ByteBuffer buffer = ByteBuffer.allocate(1024 * 64);

		synchronized (sourceChannel) {
			while (read(buffer) != -1) {
				buffer.clear();
			}
		}
	}
}
