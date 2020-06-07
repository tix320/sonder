package com.github.tix320.sonder.internal.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.LongFunction;

import com.github.tix320.kiwi.api.check.Try;
import com.github.tix320.kiwi.api.reactive.property.Property;
import com.github.tix320.kiwi.api.reactive.property.StateProperty;
import com.github.tix320.kiwi.api.util.LoopThread;
import com.github.tix320.sonder.api.client.event.ConnectionClosedEvent;
import com.github.tix320.sonder.api.client.event.ConnectionEstablishedEvent;
import com.github.tix320.sonder.api.client.event.SonderClientEvent;
import com.github.tix320.sonder.internal.common.State;
import com.github.tix320.sonder.internal.common.communication.Pack;
import com.github.tix320.sonder.internal.common.communication.PackChannel;
import com.github.tix320.sonder.internal.common.communication.SocketConnectionException;
import com.github.tix320.sonder.internal.common.util.Threads;
import com.github.tix320.sonder.internal.event.SonderEventDispatcher;

public class SocketServerConnection implements ServerConnection {

	private final InetSocketAddress address;

	private final LongFunction<Duration> contentTimeoutDurationFactory;

	private final SonderEventDispatcher<SonderClientEvent> eventDispatcher;

	private volatile PackChannel channel;

	private final StateProperty<State> state = Property.forState(State.INITIAL);

	public SocketServerConnection(InetSocketAddress address, LongFunction<Duration> contentTimeoutDurationFactory,
								  SonderEventDispatcher<SonderClientEvent> eventDispatcher) {
		this.address = address;
		this.contentTimeoutDurationFactory = contentTimeoutDurationFactory;
		this.eventDispatcher = eventDispatcher;
	}

	@Override
	public void connect(Consumer<Pack> packConsumer) throws IOException {
		boolean changed = state.compareAndSetValue(State.INITIAL, State.RUNNING);
		if (!changed) {
			throw new IllegalStateException("Already running");
		}

		this.channel = new PackChannel(SocketChannel.open(address), contentTimeoutDurationFactory, packConsumer);
		Threads.runAsync(() -> eventDispatcher.fire(new ConnectionEstablishedEvent()));
		runLoop();
	}

	@Override
	public void send(Pack pack) {
		state.checkValue(State.RUNNING);

		try {
			boolean success = channel.write(pack);
			while (!success) {
				success = channel.writeLastPack();
			}
		}
		catch (ClosedChannelException e) {
			Threads.runAsync(() -> eventDispatcher.fire(new ConnectionClosedEvent()));
			throw new SocketConnectionException("Socket connection is closed", e);
		}
		catch (IOException e) {
			Threads.runAsync(() -> eventDispatcher.fire(new ConnectionClosedEvent()));
			try {
				channel.close();
				throw new SocketConnectionException("The problem is occurred while sending data", e);
			}
			catch (IOException ex) {
				e.printStackTrace();
				throw new SocketConnectionException("The problem is occurred while closing socket", ex);
			}
		}
	}

	@Override
	public void close() throws IOException {
		boolean changed = state.compareAndSetValue(State.RUNNING, State.CLOSED);

		if (changed) {
			channel.close();
		}
	}

	private void runLoop() {
		new LoopThread(() -> {
			try {
				channel.read();
			}
			catch (AsynchronousCloseException e) {
				Threads.runAsync(() -> eventDispatcher.fire(new ConnectionClosedEvent()));
				return false;
			}
			catch (ClosedChannelException e) {
				Threads.runAsync(() -> eventDispatcher.fire(new ConnectionClosedEvent()));
				e.printStackTrace();
				return false;
			}
			catch (IOException e) {
				Threads.runAsync(() -> eventDispatcher.fire(new ConnectionClosedEvent()));
				Try.run(() -> channel.close()).onFailure(Throwable::printStackTrace);
				new SocketConnectionException("The problem is occurred while reading data", e).printStackTrace();
				return false;
			}

			return true;
		}, false).start();
	}
}
