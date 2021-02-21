package com.github.tix320.sonder.internal.server;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.kiwi.api.reactive.property.Property;
import com.github.tix320.kiwi.api.reactive.property.StateProperty;
import com.github.tix320.kiwi.api.reactive.publisher.Publisher;
import com.github.tix320.kiwi.api.reactive.publisher.SimplePublisher;
import com.github.tix320.skimp.api.generator.IDGenerator;
import com.github.tix320.skimp.api.thread.LoopThread.BreakLoopException;
import com.github.tix320.skimp.api.thread.Threads;
import com.github.tix320.sonder.api.common.Client;
import com.github.tix320.sonder.internal.common.State;
import com.github.tix320.sonder.internal.common.communication.Pack;
import com.github.tix320.sonder.internal.common.communication.SocketConnectionException;
import com.github.tix320.sonder.internal.common.communication.SonderProtocolChannel;
import com.github.tix320.sonder.internal.common.communication.SonderProtocolChannel.ContentReadInProgressException;
import com.github.tix320.sonder.internal.common.communication.SonderProtocolChannel.PackNotReadyException;
import com.github.tix320.sonder.internal.common.communication.SonderProtocolChannel.ReceivedPacket;

public final class SocketClientsSelector implements Closeable {

	private final InetSocketAddress address;

	private final ExecutorService workers;

	private final Map<Long, SelectionKey> selectionKeysById;

	private final IDGenerator clientIdGenerator;

	private final BiConsumer<Long, Pack> packConsumer;

	private volatile Selector selector;

	private volatile ServerSocketChannel serverChannel;

	private final StateProperty<State> state = Property.forState(State.INITIAL);

	private final SimplePublisher<Client> newClientsPublisher = Publisher.simple();

	private final SimplePublisher<Client> deadClientsPublisher = Publisher.simple();

	public SocketClientsSelector(InetSocketAddress address, int workersCoreCount, BiConsumer<Long, Pack> packConsumer) {
		this.address = address;
		this.packConsumer = packConsumer;
		this.selectionKeysById = new ConcurrentHashMap<>();
		this.clientIdGenerator = new IDGenerator(1); // 1 is important aspect, do not touch!
		this.workers = new ThreadPoolExecutor(workersCoreCount, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
				new SynchronousQueue<>(), Threads::daemon);
	}

	public void run() throws IOException {
		synchronized (this) {
			Selector selector = Selector.open();
			ServerSocketChannel serverChannel = ServerSocketChannel.open();
			serverChannel.bind(address);
			serverChannel.configureBlocking(false);
			serverChannel.register(selector, SelectionKey.OP_ACCEPT);

			boolean changed = state.compareAndSetValue(State.INITIAL, State.RUNNING);
			if (!changed) {
				try {
					serverChannel.close();
					selector.close();
				} catch (IOException ignored) {
				}
				throw new IllegalStateException("Already running");
			}

			this.selector = selector;
			this.serverChannel = serverChannel;

			runLoop();
		}
	}

	public void send(long clientId, Pack pack) throws ClientClosedException {
		state.checkState(State.RUNNING);

		SelectionKey selectionKey = selectionKeysById.get(clientId);
		if (selectionKey == null) {
			throw new IllegalArgumentException(String.format("Client by id %s not found", clientId));
		}

		//noinspection SynchronizationOnLocalVariableOrMethodParameter
		synchronized (selectionKey) {

			ClientConnection clientConnection = (ClientConnection) selectionKey.attachment();

			SonderProtocolChannel channel = clientConnection.channel;
			try {
				boolean success = channel.write(pack);
				if (!success) {
					enableOpsAndWakeup(selectionKey, SelectionKey.OP_WRITE);
				}
			} catch (SocketException e) {
				closeClientConnection(clientConnection);
				if (!e.getMessage().contains("Connection reset")) {
					throw new SocketConnectionException(
							String.format("An error occurs while write to client %s", clientConnection.client), e);
				} else {
					throw new ClientClosedException();
				}
			} catch (IOException e) {
				closeClientConnection(clientConnection);
				if (!e.getMessage().contains("An existing connection was forcibly closed by the remote host")) {
					throw new SocketConnectionException(
							String.format("An error occurs while write to client %s", clientConnection.client), e);
				} else {
					throw new ClientClosedException();
				}
			}
		}
	}

	@Override
	public void close() throws IOException {
		boolean changed = state.compareAndSetValue(State.RUNNING, State.CLOSED);

		if (changed) {
			state.close();
			workers.shutdown();

			ServerSocketChannel serverChannel = this.serverChannel;
			Selector selector = this.selector;

			//noinspection EmptyTryBlock
			try (serverChannel;
				 selector) { // guaranteed .close() call for every object

			}
		}
	}

	public Observable<Client> newClients() {
		return newClientsPublisher.asObservable();
	}

	public Observable<Client> deadClients() {
		return deadClientsPublisher.asObservable();
	}

	private void runLoop() {
		Selector selector = this.selector;
		Threads.createLoopThread(() -> {
			try {
				selector.select();

				Set<SelectionKey> keys = selector.selectedKeys();
				Iterator<SelectionKey> iterator = keys.iterator();
				while (iterator.hasNext()) {
					SelectionKey selectionKey = iterator.next();
					iterator.remove();
					if (selectionKey.isAcceptable()) {
						try {
							accept();
						} catch (IOException e) {
							e.printStackTrace();
						}
					} else if (selectionKey.isReadable()) {
						disableOpsAndWakeup(selectionKey, SelectionKey.OP_READ);
						runAsync(() -> read(selectionKey));
					} else if (selectionKey.isWritable()) {
						disableOpsAndWakeup(selectionKey, SelectionKey.OP_WRITE);
						runAsync(() -> write(selectionKey));
					} else {
						selectionKey.cancel();
					}
				}
			} catch (CancelledKeyException e) {
				// bye key
			} catch (ClosedSelectorException e) {
				throw new BreakLoopException();
			} catch (IOException e) {
				new SocketConnectionException("The problem is occurred in selector work", e).printStackTrace();
				throw new BreakLoopException();
			}
		}).start();
	}

	private void accept() throws IOException {
		SocketChannel clientChannel = serverChannel.accept();
		clientChannel.configureBlocking(false);
		long clientId = clientIdGenerator.next();
		SonderProtocolChannel sonderProtocolChannel = new SonderProtocolChannel(clientChannel);

		InetSocketAddress remoteAddress = (InetSocketAddress) clientChannel.getRemoteAddress();
		Client client = new Client(clientId, remoteAddress);
		ClientConnection clientConnection = new ClientConnection(client, sonderProtocolChannel);

		SelectionKey selectionKey = clientChannel.register(selector, SelectionKey.OP_READ, clientConnection);

		selectionKeysById.put(clientId, selectionKey);

		newClientsPublisher.publish(client);
	}

	private void read(SelectionKey selectionKey) {
		ClientConnection clientConnection = (ClientConnection) selectionKey.attachment();
		SonderProtocolChannel channel = clientConnection.channel;
		ReceivedPacket pack;
		try {
			pack = channel.read();
		} catch (ClosedChannelException e) {
			closeClientConnection(clientConnection);
			return;
		} catch (SocketException e) {
			closeClientConnection(clientConnection);
			if (!e.getMessage().contains("Connection reset")) {
				throw new SocketConnectionException(
						String.format("An error occurs while read from client %s", clientConnection.client), e);
			}
			return;
		} catch (IOException e) {
			closeClientConnection(clientConnection);
			if (!e.getMessage().contains("An existing connection was forcibly closed by the remote host")) {
				throw new SocketConnectionException(
						String.format("An error occurs while read from client %s", clientConnection.client), e);
			}

			return;
		} catch (PackNotReadyException e) {
			enableOpsAndWakeup(selectionKey, SelectionKey.OP_READ);
			return;
		} catch (ContentReadInProgressException e) {
			return;
		}

		pack.state().subscribe(state -> {
			switch (state) {
				case EMPTY:
				case COMPLETED:
					enableOpsAndWakeup(selectionKey, SelectionKey.OP_READ);
					break;

				default:
					throw new IllegalStateException("Unexpected value: " + state);
			}
		});


		// ChannelUtils.setChannelFinishedWarningHandler(blockingChannel,
		// 		duration -> String.format("SONDER WARNING: Client: %s - %s not finished in %s",
		// 				clientConnection.client, CertainReadableByteChannel.class.getSimpleName(), duration));

		runAsync(() -> packConsumer.accept(clientConnection.client.getId(), pack.getPack()));
	}

	private void write(SelectionKey selectionKey) {
		ClientConnection clientConnection = (ClientConnection) selectionKey.attachment();
		SonderProtocolChannel channel = clientConnection.channel;

		try {
			boolean success = channel.writeLastPack();
			if (!success) {
				enableOpsAndWakeup(selectionKey, SelectionKey.OP_WRITE);
			}
		} catch (IOException e) {
			if (!e.getMessage().contains("An existing connection was forcibly closed by the remote host")) {
				throw new SocketConnectionException(
						String.format("An error occurs while write to client %s", clientConnection.client), e);
			}

			closeClientConnection(clientConnection);
		}
	}

	private void enableOpsAndWakeup(SelectionKey selectionKey, int op) {
		try {
			selectionKey.interestOpsOr(op);
			selector.wakeup();
		} catch (CancelledKeyException | ClosedSelectorException e) {
			// bye key
		}
	}

	private void disableOpsAndWakeup(SelectionKey selectionKey, int op) {
		try {
			selectionKey.interestOpsAnd(~op);
			selector.wakeup();
		} catch (CancelledKeyException | ClosedSelectorException e) {
			// bye key
		}
	}

	private void closeClientConnection(ClientConnection clientConnection) {
		SelectionKey selectionKey = selectionKeysById.remove(clientConnection.client.getId());
		if (selectionKey != null) {

			//noinspection SynchronizationOnLocalVariableOrMethodParameter
			synchronized (selectionKey) {
				try {
					SonderProtocolChannel sonderProtocolChannel = clientConnection.channel;
					sonderProtocolChannel.close();
				} catch (IOException e) {
					new SocketConnectionException(String.format("An error occurs while closing channel of client %s",
							clientConnection.client), e).printStackTrace();
				} finally {
					deadClientsPublisher.publish(clientConnection.client);
				}
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

	private static class ClientConnection {
		private final Client client;
		private final SonderProtocolChannel channel;

		private ClientConnection(Client client, SonderProtocolChannel channel) {
			this.client = client;
			this.channel = channel;
		}
	}
}
