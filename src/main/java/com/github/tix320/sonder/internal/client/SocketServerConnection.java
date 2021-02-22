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
import com.github.tix320.sonder.internal.common.communication.Pack;
import com.github.tix320.sonder.internal.common.communication.SocketConnectionException;
import com.github.tix320.sonder.internal.common.communication.SonderProtocolChannel;
import com.github.tix320.sonder.internal.common.communication.SonderProtocolChannel.ContentReadInProgressException;
import com.github.tix320.sonder.internal.common.communication.SonderProtocolChannel.PackNotReadyException;
import com.github.tix320.sonder.internal.common.communication.SonderProtocolChannel.ReceivedPacket;

public final class SocketServerConnection {

	private final ExecutorService workers = new ThreadPoolExecutor(1, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
			new SynchronousQueue<>(), Threads::daemon);

	private final InetSocketAddress address;

	private final Consumer<Pack> packConsumer;

	private volatile SonderProtocolChannel channel;

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

			this.channel = new SonderProtocolChannel(socketChannel);
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
				ReceivedPacket pack = channel.read();
				CountDownLatch latch = new CountDownLatch(1);

				pack.state().subscribe(state -> {
					switch (state) {
						case EMPTY:
							throw new IllegalStateException("Client is non-blocking");
						case COMPLETED:
							latch.countDown();
							break;
						default:
							throw new IllegalStateException("Unexpected value: " + state);
					}
				});


				runAsync(() -> packConsumer.accept(pack.getPack()));

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
				if (!e.getMessage().contains("An existing connection was forcibly closed by the remote host")) {
					new SocketConnectionException("The problem is occurred while reading data", e).printStackTrace();
				}

				throw new BreakLoopException();
			} catch (PackNotReadyException ignored) {
				// client side channel is blocking, so we are continue next read
			} catch (ContentReadInProgressException ignored) {
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
