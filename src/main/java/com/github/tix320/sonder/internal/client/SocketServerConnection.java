package com.github.tix320.sonder.internal.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.util.concurrent.*;
import java.util.function.Consumer;

import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.kiwi.api.reactive.property.Property;
import com.github.tix320.kiwi.api.reactive.property.StateProperty;
import com.github.tix320.skimp.api.thread.LoopThread;
import com.github.tix320.skimp.api.thread.LoopThread.BreakLoopException;
import com.github.tix320.skimp.api.thread.Threads;
import com.github.tix320.sonder.api.client.ConnectionState;
import com.github.tix320.sonder.api.common.communication.CertainReadableByteChannel;
import com.github.tix320.sonder.internal.common.ChannelUtils;
import com.github.tix320.sonder.internal.common.communication.Pack;
import com.github.tix320.sonder.internal.common.communication.PackChannel;
import com.github.tix320.sonder.internal.common.communication.SocketConnectionException;

public final class SocketServerConnection {

	private final ExecutorService workers = new ThreadPoolExecutor(1, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
			new SynchronousQueue<>(), Threads::daemon);

	private final InetSocketAddress address;

	private final Consumer<Pack> packConsumer;

	private volatile PackChannel channel;

	private volatile LoopThread thread;

	private final StateProperty<ConnectionState> state;

	public SocketServerConnection(InetSocketAddress address, Consumer<Pack> packConsumer) {
		this.address = address;
		this.packConsumer = packConsumer;
		this.state = Property.forState(ConnectionState.IDLE);
	}

	public void connect() throws IOException {
		synchronized (this) {
			SocketChannel socketChannel = SocketChannel.open(address);
			boolean changed = state.compareAndSetValue(ConnectionState.IDLE, ConnectionState.CONNECTED);
			if (!changed) {
				try {
					socketChannel.close();
				} catch (IOException ignored) {
				}

				throw new IllegalStateException("Already running");
			}

			this.channel = new PackChannel(socketChannel);
			this.thread = createLoopThread();
			this.thread.start();
		}
	}

	public void send(Pack pack) {
		state.checkState(ConnectionState.CONNECTED);

		try {
			boolean success = channel.write(pack);
			while (!success) {
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				success = channel.writeLastPack();
			}
		} catch (ClosedChannelException e) {
			resetConnection();
			throw new SocketConnectionException("Socket connection is closed", e);
		} catch (IOException e) {
			resetConnection();
			try {
				channel.close();
				throw new SocketConnectionException("The problem is occurred while sending data", e);
			} catch (IOException ex) {
				e.printStackTrace();
				throw new SocketConnectionException("The problem is occurred while closing socket", ex);
			}
		}
	}

	public boolean close() throws IOException {
		boolean changed = state.compareAndSetValue(ConnectionState.CONNECTED, ConnectionState.CLOSED);

		if (changed) {
			workers.shutdown();
			thread.stop();
			channel.close();
		}

		return changed;
	}

	public Observable<ConnectionState> state() {
		return state.asObservable();
	}

	private LoopThread createLoopThread() {
		return Threads.createLoopThread(() -> {
			try {
				Pack pack = channel.read();

				if (pack != null) {
					CountDownLatch latch = new CountDownLatch(1);
					CertainReadableByteChannel contentChannel = pack.channel();
					runAsync(() -> {
						packConsumer.accept(pack);
					});

					ChannelUtils.setChannelFinishedWarningHandler(contentChannel,
							duration -> String.format("SONDER WARNING: %s not finished in %s",
									CertainReadableByteChannel.class.getSimpleName(), duration));

					contentChannel.completeness().subscribe(none -> latch.countDown());

					try {
						latch.await();
					} catch (InterruptedException e) {
						throw new BreakLoopException();
					}
				}

			} catch (ClosedChannelException e) {
				resetConnection();
				throw new BreakLoopException();
			} catch (IOException e) {
				resetConnection();
				if (!e.getMessage().contains("An existing connection was forcibly closed by the remote host")) {
					new SocketConnectionException("The problem is occurred while reading data", e).printStackTrace();
				}

				throw new BreakLoopException();
			}
		});
	}

	private void resetConnection() {
		synchronized (this) {
			boolean changed = this.state.compareAndSetValue(ConnectionState.CONNECTED, ConnectionState.IDLE);
			if (changed) {
				try {
					thread.stop();
					channel.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
				this.channel = null;
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
					t.printStackTrace();
				}
			});
		} catch (RejectedExecutionException ignored) {
			// already closed
		}
	}
}
