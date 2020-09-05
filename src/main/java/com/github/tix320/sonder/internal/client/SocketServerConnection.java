package com.github.tix320.sonder.internal.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.LongFunction;

import com.github.tix320.kiwi.api.check.Try;
import com.github.tix320.kiwi.api.reactive.property.Property;
import com.github.tix320.kiwi.api.reactive.property.StateProperty;
import com.github.tix320.kiwi.api.util.LoopThread.BreakLoopException;
import com.github.tix320.kiwi.api.util.Threads;
import com.github.tix320.sonder.api.client.event.ConnectionClosedEvent;
import com.github.tix320.sonder.api.client.event.ConnectionEstablishedEvent;
import com.github.tix320.sonder.api.common.communication.CertainReadableByteChannel;
import com.github.tix320.sonder.api.common.communication.LimitedReadableByteChannel;
import com.github.tix320.sonder.api.common.event.SonderEventDispatcher;
import com.github.tix320.sonder.internal.common.State;
import com.github.tix320.sonder.internal.common.communication.Pack;
import com.github.tix320.sonder.internal.common.communication.PackChannel;
import com.github.tix320.sonder.internal.common.communication.SocketConnectionException;

public class SocketServerConnection implements ServerConnection {

	private final ExecutorService workers = new ThreadPoolExecutor(1, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
			new SynchronousQueue<>(), Threads::daemon);

	private final InetSocketAddress address;

	private final LongFunction<Duration> contentTimeoutDurationFactory;

	private final SonderEventDispatcher eventDispatcher;

	private volatile PackChannel channel;

	private final StateProperty<State> state = Property.forState(State.INITIAL);

	public SocketServerConnection(InetSocketAddress address, LongFunction<Duration> contentTimeoutDurationFactory,
								  SonderEventDispatcher eventDispatcher) {
		this.address = address;
		this.contentTimeoutDurationFactory = contentTimeoutDurationFactory;
		this.eventDispatcher = eventDispatcher;
	}

	@Override
	public synchronized void connect(Consumer<Pack> packConsumer) throws IOException {
		SocketChannel socketChannel = SocketChannel.open(address);
		boolean changed = state.compareAndSetValue(State.INITIAL, State.RUNNING);
		if (!changed) {
			throw new IllegalStateException("Already running");
		}

		this.channel = new PackChannel(socketChannel, contentTimeoutDurationFactory);
		eventDispatcher.fire(new ConnectionEstablishedEvent());
		runLoop(packConsumer);
	}

	@Override
	public void send(Pack pack) {
		state.checkState(State.RUNNING);

		try {
			boolean success = channel.write(pack);
			while (!success) {
				success = channel.writeLastPack();
			}
		}
		catch (ClosedChannelException e) {
			eventDispatcher.fire(new ConnectionClosedEvent());
			throw new SocketConnectionException("Socket connection is closed", e);
		}
		catch (IOException e) {
			eventDispatcher.fire(new ConnectionClosedEvent());
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
	public boolean close() throws IOException {
		boolean changed = state.compareAndSetValue(State.RUNNING, State.CLOSED);

		if (changed) {
			workers.shutdown();
			channel.close();
		}

		return changed;
	}

	@Override
	public State getState() {
		return state.getValue();
	}

	private void runLoop(Consumer<Pack> packConsumer) {
		Threads.createLoopThread(() -> {
			try {
				Pack pack = channel.read();

				if (pack != null) {
					CertainReadableByteChannel contentChannel = pack.channel();
					runAsync(() -> packConsumer.accept(pack));
					if (contentChannel instanceof LimitedReadableByteChannel) {
						Duration timeoutDuration = contentTimeoutDurationFactory.apply(
								contentChannel.getContentLength());

						LimitedReadableByteChannel limitedReadableByteChannel = (LimitedReadableByteChannel) contentChannel;
						try {
							limitedReadableByteChannel.onFinish().get(timeoutDuration);
						}
						catch (InterruptedException e) {
							throw new IllegalStateException(e);
						}
					}
				}

			}
			catch (AsynchronousCloseException e) {
				resetConnection();
				throw new BreakLoopException();
			}
			catch (ClosedChannelException e) {
				resetConnection();
				e.printStackTrace();
				throw new BreakLoopException();
			}
			catch (IOException e) {
				resetConnection();
				if (!e.getMessage().contains("An existing connection was forcibly closed by the remote host")) {
					new SocketConnectionException("The problem is occurred while reading data", e).printStackTrace();
				}

				throw new BreakLoopException();
			}
		}).start();
	}

	private void resetConnection() {
		synchronized (this) {
			boolean changed = this.state.compareAndSetValue(State.RUNNING, State.INITIAL);
			if (changed) {
				Try.run(() -> channel.close()).onFailure(Throwable::printStackTrace);
				this.channel = null;
				eventDispatcher.fire(new ConnectionClosedEvent());
			}
		}
	}

	private void runAsync(Runnable runnable) {
		try {
			workers.submit(() -> {
				try {
					runnable.run();
				}
				catch (Throwable t) {
					t.printStackTrace();
				}
			});
		}
		catch (RejectedExecutionException ignored) {
			// already closed
		}
	}
}
