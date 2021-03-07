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
import java.util.concurrent.atomic.AtomicReference;

import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.kiwi.api.reactive.publisher.Publisher;
import com.github.tix320.kiwi.api.reactive.publisher.SimplePublisher;
import com.github.tix320.skimp.api.exception.ExceptionUtils;
import com.github.tix320.skimp.api.generator.IDGenerator;
import com.github.tix320.skimp.api.thread.LoopThread.BreakLoopException;
import com.github.tix320.skimp.api.thread.Threads;
import com.github.tix320.sonder.api.common.Client;
import com.github.tix320.sonder.api.common.communication.channel.BlockingReadableByteChannel;
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

public final class SocketClientsSelector implements Closeable {

	private final InetSocketAddress address;

	private final ExecutorService workers;

	private final Map<Long, SelectionKey> selectionKeysById;

	private final IDGenerator clientIdGenerator;

	private final PackConsumer packConsumer;

	private volatile Selector selector;
	private volatile ServerSocketChannel serverChannel;

	private final AtomicReference<State> state = new AtomicReference<>(State.INITIAL);

	private final SimplePublisher<Client> newClientsPublisher = Publisher.simple();
	private final SimplePublisher<Client> deadClientsPublisher = Publisher.simple();

	public SocketClientsSelector(InetSocketAddress address, PackConsumer packConsumer) {
		this.address = address;
		this.packConsumer = packConsumer;
		this.selectionKeysById = new ConcurrentHashMap<>();
		this.clientIdGenerator = new IDGenerator(1);
		this.workers = new ThreadPoolExecutor(5, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(),
				Threads::daemon);
	}

	public void run() throws IOException {
		synchronized (this) {
			Selector selector = Selector.open();
			ServerSocketChannel serverChannel = ServerSocketChannel.open();
			serverChannel.bind(address);
			serverChannel.configureBlocking(false);
			serverChannel.register(selector, SelectionKey.OP_ACCEPT);

			boolean changed = state.compareAndSet(State.INITIAL, State.RUNNING);
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
		State state = this.state.get();
		if (state != State.RUNNING) {
			throw new IllegalStateException(state.toString());
		}

		pack = new Pack(pack.getHeaders(), new BlockingPreventFiniteReadableByteChannel(pack.contentChannel()));

		SelectionKey selectionKey = selectionKeysById.get(clientId);
		if (selectionKey == null) {
			throw new IllegalArgumentException(String.format("Client by id %s not found", clientId));
		}

		//noinspection SynchronizationOnLocalVariableOrMethodParameter
		synchronized (selectionKey) {

			ClientConnection clientConnection = (ClientConnection) selectionKey.attachment();

			SonderProtocolChannel channel = clientConnection.channel;
			try {
				boolean success = channel.tryWrite(pack);
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
		boolean changed = state.compareAndSet(State.RUNNING, State.CLOSED);

		if (changed) {
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
							ExceptionUtils.applyToUncaughtExceptionHandler(e);
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
				SocketConnectionException socketConnectionException = new SocketConnectionException(
						"The problem is occurred in selector work", e);
				ExceptionUtils.applyToUncaughtExceptionHandler(socketConnectionException);
				throw new BreakLoopException();
			}
		}).start();
	}

	private void accept() throws IOException {
		SocketChannel socketChannel = serverChannel.accept();
		socketChannel.configureBlocking(false);
		long clientId = clientIdGenerator.next();
		SonderProtocolChannel sonderProtocolChannel = new SonderProtocolChannel(socketChannel);

		InetSocketAddress remoteAddress = (InetSocketAddress) socketChannel.getRemoteAddress();
		Client client = new Client(clientId, remoteAddress);
		ClientConnection clientConnection = new ClientConnection(client, sonderProtocolChannel);

		SelectionKey selectionKey = socketChannel.register(selector, SelectionKey.OP_READ, clientConnection);

		selectionKeysById.put(clientId, selectionKey);

		newClientsPublisher.publish(client);
	}

	private void read(SelectionKey selectionKey) {
		ClientConnection clientConnection = (ClientConnection) selectionKey.attachment();
		SonderProtocolChannel sonderProtocolChannel = clientConnection.channel;
		ReceivedPack receivedPack;
		try {
			receivedPack = sonderProtocolChannel.tryRead();
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
		} catch (PackAlreadyReadException e) {
			clientConnection.getBlockingChannel().notifyForAvailability();
			return;
		}

		ByteChannel socketChannel = sonderProtocolChannel.getSourceChannel();

		BlockingReadableByteChannel blockingWrapperChannel = new BlockingReadableByteChannel(socketChannel);

		clientConnection.setBlockingChannel(blockingWrapperChannel);

		blockingWrapperChannel.emptiness().subscribe(none -> enableOpsAndWakeup(selectionKey, SelectionKey.OP_READ));

		FiniteReadableByteChannel contentChannel;

		if (receivedPack.getContentLength() == 0) {
			contentChannel = EmptyReadableByteChannel.SELF;
			sonderProtocolChannel.resetReadState();
			enableOpsAndWakeup(selectionKey, SelectionKey.OP_READ);
		} else {
			contentChannel = new CleanableFiniteReadableByteChannel(
					new LimitedReadableByteChannel(blockingWrapperChannel, receivedPack.getContentLength()));
			contentChannel.completeness().subscribe(none -> {
				clientConnection.setBlockingChannel(null);
				sonderProtocolChannel.resetReadState();
				enableOpsAndWakeup(selectionKey, SelectionKey.OP_READ);
			});
		}

		Pack pack = new Pack(receivedPack.getHeaders(), contentChannel);

		runAsync(() -> packConsumer.consume(clientConnection.client.getId(), pack));
	}

	private void write(SelectionKey selectionKey) {
		ClientConnection clientConnection = (ClientConnection) selectionKey.attachment();
		SonderProtocolChannel channel = clientConnection.channel;

		try {
			boolean success = channel.continueWriting();
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
				} catch (IOException ignored) {

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
					ExceptionUtils.applyToUncaughtExceptionHandler(t);
				}
			});
		} catch (RejectedExecutionException ignored) {
			// already closed
		}
	}

	private static class ClientConnection {
		private final Client client;
		private final SonderProtocolChannel channel;
		private volatile BlockingReadableByteChannel blockingChannel;

		private ClientConnection(Client client, SonderProtocolChannel channel) {
			this.client = client;
			this.channel = channel;
		}

		public BlockingReadableByteChannel getBlockingChannel() {
			return blockingChannel;
		}

		public void setBlockingChannel(BlockingReadableByteChannel blockingChannel) {
			this.blockingChannel = blockingChannel;
		}
	}

	private enum State {
		INITIAL,
		RUNNING,
		CLOSED
	}

	public interface PackConsumer {

		void consume(long clientId, Pack pack);
	}
}
