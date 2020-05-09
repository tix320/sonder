package com.github.tix320.sonder.internal.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.function.LongFunction;

import com.github.tix320.kiwi.api.check.Try;
import com.github.tix320.kiwi.api.reactive.observable.Observable;
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

	private PackChannel channel;

	private final StateProperty<State> state = Property.forState(State.INITIAL);

	public SocketServerConnection(InetSocketAddress address, LongFunction<Duration> contentTimeoutDurationFactory,
								  SonderEventDispatcher<SonderClientEvent> eventDispatcher) {
		this.address = address;
		this.contentTimeoutDurationFactory = contentTimeoutDurationFactory;
		this.eventDispatcher = eventDispatcher;
	}

	@Override
	public synchronized void connect() throws IOException {
		boolean changed = state.compareAndSetValue(State.INITIAL, State.RUNNING);
		if (!changed) {
			throw new IllegalStateException("Already runned");
		}

		this.channel = new PackChannel(SocketChannel.open(address), contentTimeoutDurationFactory);
		Threads.runAsync(() -> eventDispatcher.fire(new ConnectionEstablishedEvent()));
		runLoop();
	}

	@Override
	public Observable<Pack> incomingRequests() {
		state.checkValue(State.RUNNING);

		return channel.packs();
	}

	@Override
	public void send(Pack pack) {
		state.checkValue(State.RUNNING);

		try {
			channel.write(pack);
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
	public synchronized void close() throws IOException {
		boolean changed = state.compareAndSetValue(State.RUNNING, State.CLOSED);

		if (changed) {
			if (channel != null) {
				channel.close();
			}
		}
	}

	private void runLoop() {
		new LoopThread(() -> {
			try {
				channel.read();
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
