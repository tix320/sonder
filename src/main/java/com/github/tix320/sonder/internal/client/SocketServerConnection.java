package com.github.tix320.sonder.internal.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ByteChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.util.concurrent.*;
import java.util.function.Consumer;

import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.kiwi.api.reactive.property.Property;
import com.github.tix320.kiwi.api.reactive.property.StateProperty;
import com.github.tix320.skimp.api.exception.ExceptionUtils;
import com.github.tix320.skimp.api.thread.LoopThread;
import com.github.tix320.skimp.api.thread.LoopThread.BreakLoopException;
import com.github.tix320.skimp.api.thread.Threads;
import com.github.tix320.sonder.api.client.ConnectionState;
import com.github.tix320.sonder.api.common.communication.channel.EmptyReadableByteChannel;
import com.github.tix320.sonder.api.common.communication.channel.FiniteReadableByteChannel;
import com.github.tix320.sonder.api.common.communication.channel.LimitedReadableByteChannel;
import com.github.tix320.sonder.internal.common.communication.Pack;
import com.github.tix320.sonder.internal.common.communication.SocketConnectionException;
import com.github.tix320.sonder.internal.common.communication.SonderProtocolChannel;
import com.github.tix320.sonder.internal.common.communication.SonderProtocolChannel.PackAlreadyReadException;
import com.github.tix320.sonder.internal.common.communication.SonderProtocolChannel.PackNotReadyException;
import com.github.tix320.sonder.internal.common.communication.SonderProtocolChannel.ReceivedPack;
import com.github.tix320.sonder.internal.common.communication.channel.BlockingPreventFiniteReadableByteChannel;
import com.github.tix320.sonder.internal.common.communication.channel.CleanableFiniteReadableByteChannel;

public final class SocketServerConnection {

	private final ExecutorService workers = new ThreadPoolExecutor(1, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
			new SynchronousQueue<>(), Threads::daemon);

	private final InetSocketAddress address;

	private final Consumer<Pack> packConsumer;

	private volatile SonderProtocolChannel sonderProtocolChannel;

	private volatile LoopThread thread;

	private final StateProperty<ConnectionState> state;

	public SocketServerConnection(InetSocketAddress address, Consumer<Pack> packConsumer) {
		this.address = address;
		this.packConsumer = packConsumer;
		this.state = Property.forState(ConnectionState.IDLE);
	}

	public void connect() throws IOException {
		synchronized (this) {
			state.checkState(ConnectionState.IDLE);

			SocketChannel socketChannel = SocketChannel.open(address);

			this.sonderProtocolChannel = new SonderProtocolChannel(socketChannel);
			this.thread = createLoopThread();
			this.thread.start();

			boolean changed = state.compareAndSetValue(ConnectionState.IDLE, ConnectionState.CONNECTED);
			if (!changed) {
				throw new IllegalStateException();
			}
		}
	}

	public void send(Pack pack) {
		state.checkState(ConnectionState.CONNECTED);

		pack = new Pack(pack.getHeaders(), new BlockingPreventFiniteReadableByteChannel(pack.contentChannel()));

		try {
			boolean success = sonderProtocolChannel.tryWrite(pack);
			while (!success) {
				try {
					Thread.sleep(500);
				} catch (InterruptedException e) {
					ExceptionUtils.applyToUncaughtExceptionHandler(e);
				}
				success = sonderProtocolChannel.continueWriting();
			}
		} catch (ClosedChannelException e) {
			resetConnection();
			throw new SocketConnectionException("Socket connection is closed", e);
		} catch (IOException e) {
			resetConnection();
			throw new SocketConnectionException("The problem is occurred while sending data", e);
		}
	}

	public void close() throws IOException {
		boolean changed = state.compareAndSetValue(ConnectionState.CONNECTED, ConnectionState.CLOSED);

		if (changed) {
			workers.shutdown();
			thread.stop();
			sonderProtocolChannel.close();
		}
	}

	public Observable<ConnectionState> state() {
		return state.asObservable();
	}

	private LoopThread createLoopThread() {
		return Threads.createLoopThread(() -> {
			try {
				ReceivedPack receivedPack = sonderProtocolChannel.tryRead();
				CountDownLatch latch = new CountDownLatch(1);

				FiniteReadableByteChannel contentChannel;

				if (receivedPack.getContentLength() == 0) {
					contentChannel = EmptyReadableByteChannel.SELF;
				} else {
					ByteChannel socketChannel = sonderProtocolChannel.getSourceChannel();
					contentChannel = new CleanableFiniteReadableByteChannel(
							new LimitedReadableByteChannel(socketChannel, receivedPack.getContentLength()));
				}

				contentChannel.completeness().subscribe(none -> {
					sonderProtocolChannel.resetReadState();
					latch.countDown();
				});

				Pack pack = new Pack(receivedPack.getHeaders(), contentChannel);

				runAsync(() -> packConsumer.accept(pack));

				try {
					latch.await();
				} catch (InterruptedException e) {
					throw new BreakLoopException();
				}

			} catch (ClosedChannelException e) {
				resetConnection();
				throw new BreakLoopException();
			} catch (IOException e) {
				resetConnection();
				if (e.getMessage() == null || !e.getMessage()
						.contains("An existing connection was forcibly closed by the remote host")) {
					SocketConnectionException socketConnectionException = new SocketConnectionException(
							"The problem is occurred while reading data", e);
					ExceptionUtils.applyToUncaughtExceptionHandler(socketConnectionException);
				}

				throw new BreakLoopException();
			} catch (PackNotReadyException ignored) {
				try {
					Thread.sleep(100); // wait and continue
				} catch (InterruptedException e) {
					throw new BreakLoopException();
				}
			} catch (PackAlreadyReadException ignored) {
				throw new IllegalStateException("Because of we are sleep until channel state is COMPLETED");
			}
		});
	}

	private void resetConnection() {
		synchronized (this) {
			boolean changed = this.state.compareAndSetValue(ConnectionState.CONNECTED, ConnectionState.IDLE);
			if (changed) {
				try {
					thread.stop();
					sonderProtocolChannel.close();
				} catch (IOException ignored) {
				}
				this.sonderProtocolChannel = null;
				this.thread = null;
			}
		}
	}

	private void runAsync(Runnable runnable) {
		try {
			workers.submit(() -> {
				try {
					runnable.run();
				} catch (Throwable t) {
					ExceptionUtils.applyToUncaughtExceptionHandler(t);
				}
			});
		} catch (RejectedExecutionException ignored) {
			// already closed
		}
	}
}
