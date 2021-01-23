package com.github.tix320.sonder.internal.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.channels.*;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongFunction;

import com.github.tix320.kiwi.api.reactive.property.Property;
import com.github.tix320.kiwi.api.reactive.property.StateProperty;
import com.github.tix320.skimp.api.generator.IDGenerator;
import com.github.tix320.skimp.api.object.None;
import com.github.tix320.skimp.api.thread.LoopThread.BreakLoopException;
import com.github.tix320.skimp.api.thread.Threads;
import com.github.tix320.sonder.api.common.communication.CertainReadableByteChannel;
import com.github.tix320.sonder.api.common.communication.LimitedReadableByteChannel;
import com.github.tix320.sonder.api.server.event.ClientConnectionClosedEvent;
import com.github.tix320.sonder.api.server.event.NewClientConnectionEvent;
import com.github.tix320.sonder.internal.common.State;
import com.github.tix320.sonder.internal.common.communication.InvalidPackException;
import com.github.tix320.sonder.internal.common.communication.Pack;
import com.github.tix320.sonder.internal.common.communication.PackChannel;
import com.github.tix320.sonder.internal.common.communication.SocketConnectionException;
import com.github.tix320.sonder.internal.common.event.EventDispatcher;

public final class SocketClientsSelector implements ClientsSelector {

	private final InetSocketAddress address;

	private final ExecutorService workers;

	private final Map<Long, SelectionKey> selectionKeysById;

	private final IDGenerator clientIdGenerator;

	private final LongFunction<Duration> contentTimeoutDurationFactory;

	private final EventDispatcher eventDispatcher;

	private volatile Selector selector;

	private volatile ServerSocketChannel serverChannel;

	private final StateProperty<State> state = Property.forState(State.INITIAL);

	public SocketClientsSelector(InetSocketAddress address, LongFunction<Duration> contentTimeoutDurationFactory,
								 int workersCoreCount, EventDispatcher eventDispatcher) {
		this.address = address;
		this.selectionKeysById = new ConcurrentHashMap<>();
		this.clientIdGenerator = new IDGenerator(1); // 1 is important aspect, do not touch!
		this.contentTimeoutDurationFactory = contentTimeoutDurationFactory;
		this.workers = new ThreadPoolExecutor(workersCoreCount, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
				new SynchronousQueue<>(), Threads::daemon);
		this.eventDispatcher = eventDispatcher;
	}

	public void run(Consumer<ClientPack> packConsumer) throws IOException {
		Selector selector = Selector.open();
		ServerSocketChannel serverChannel = ServerSocketChannel.open();
		serverChannel.bind(address);
		serverChannel.configureBlocking(false);
		serverChannel.register(selector, SelectionKey.OP_ACCEPT);

		boolean changed = state.compareAndSetValue(State.INITIAL, State.RUNNING);
		if (!changed) {
			throw new IllegalStateException("Already running");
		}

		this.selector = selector;
		this.serverChannel = serverChannel;

		runLoop(packConsumer);
	}

	@Override
	public void send(ClientPack clientPack) throws ClientClosedException {
		state.checkState(State.RUNNING);

		long clientId = clientPack.getClientId();

		SelectionKey selectionKey = selectionKeysById.get(clientId);
		if (selectionKey == null) {
			throw new IllegalArgumentException(String.format("Client by id %s not found", clientId));
		}

		Client client = (Client) selectionKey.attachment();

		if (client.isConnected.get()) {
			PackChannel channel = client.channel;
			try {
				boolean success = channel.write(clientPack.getPack());
				if (!success) {
					selectionKey.interestOpsOr(SelectionKey.OP_WRITE);
					selector.wakeup();
				}
			}
			catch (SocketException e) {
				closeClientConnection(client);
				if (!e.getMessage().contains("Connection reset")) {
					throw new SocketConnectionException(
							String.format("An error occurs while write to client %s", client.id), e);
				}
				else {
					throw new ClientClosedException();
				}
			}
			catch (IOException e) {
				closeClientConnection(client);
				if (!e.getMessage().contains("An existing connection was forcibly closed by the remote host")) {
					throw new SocketConnectionException(
							String.format("An error occurs while write to client %s", client.id), e);
				}
				else {
					throw new ClientClosedException();
				}
			}
		}
		else {
			throw new IllegalArgumentException(String.format("Client %s is disconnected", clientId));
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

			try (serverChannel;
				 selector) { // guaranteed .close() call for every object

			}
		}
	}

	private void runLoop(Consumer<ClientPack> packConsumer) {
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
						}
						catch (IOException e) {
							e.printStackTrace();
						}
					}
					else if (selectionKey.isReadable()) {
						selectionKey.interestOpsAnd(~SelectionKey.OP_READ);
						runAsync(() -> read(selectionKey, packConsumer));
					}

					else if (selectionKey.isWritable()) {
						selectionKey.interestOpsAnd(~SelectionKey.OP_WRITE);
						runAsync(() -> write(selectionKey));
					}
					else {
						selectionKey.cancel();
					}
				}
			}
			catch (CancelledKeyException e) {
				// bye key
			}
			catch (ClosedSelectorException e) {
				throw new BreakLoopException();
			}
			catch (IOException e) {
				new SocketConnectionException("The problem is occurred in selector work", e).printStackTrace();
				throw new BreakLoopException();
			}
		}).start();
	}

	private void accept() throws IOException {
		SocketChannel clientChannel = serverChannel.accept();
		clientChannel.configureBlocking(false);
		long clientId = clientIdGenerator.next();
		PackChannel packChannel = new PackChannel(clientChannel, contentTimeoutDurationFactory);

		Client client = new Client(clientId, packChannel);

		SelectionKey selectionKey = clientChannel.register(selector, SelectionKey.OP_READ, client);

		selectionKeysById.put(clientId, selectionKey);

		eventDispatcher.fire(new NewClientConnectionEvent(clientId));
	}

	private void read(SelectionKey selectionKey, Consumer<ClientPack> packConsumer) throws InvalidPackException {
		Client client = (Client) selectionKey.attachment();
		PackChannel channel = client.channel;
		Pack pack;
		try {
			pack = channel.read();
		}
		catch (ClosedChannelException e) {
			closeClientConnection(client);
			return;
		}
		catch (SocketException e) {
			closeClientConnection(client);
			if (!e.getMessage().contains("Connection reset")) {
				throw new SocketConnectionException(
						String.format("An error occurs while read from client %s", client.id), e);
			}
			return;
		}
		catch (IOException e) {
			closeClientConnection(client);
			if (!e.getMessage().contains("An existing connection was forcibly closed by the remote host")) {
				throw new SocketConnectionException(
						String.format("An error occurs while read from client %s", client.id), e);
			}

			return;
		}

		if (pack == null) {
			try {
				selectionKey.interestOpsOr(SelectionKey.OP_READ);
				selector.wakeup();
			}
			catch (CancelledKeyException e) {
				// bye key
			}
		}
		else {
			CertainReadableByteChannel contentChannel = pack.channel();
			ClientPack clientPack = new ClientPack(client.id, pack);
			runAsync(() -> {
				try {
					packConsumer.accept(clientPack);
				}
				finally {
					try {
						contentChannel.readRemainingInVain();
					}
					catch (IOException e) {
						e.printStackTrace();
					}
				}
			});
			if (contentChannel instanceof LimitedReadableByteChannel) {

				Duration timeoutDuration = contentTimeoutDurationFactory.apply(contentChannel.getContentLength());

				LimitedReadableByteChannel limitedReadableByteChannel = (LimitedReadableByteChannel) contentChannel;
				limitedReadableByteChannel.onFinish().getOnTimout(timeoutDuration, () -> None.SELF).subscribe(none -> {
					try {
						selectionKey.interestOpsOr(SelectionKey.OP_READ);
						selector.wakeup();
					}
					catch (CancelledKeyException e) {
						// bye key
					}
				});
			}
			else {
				try {
					selectionKey.interestOpsOr(SelectionKey.OP_READ);
					selector.wakeup();
				}
				catch (CancelledKeyException e) {
					// bye key
				}
			}
		}
	}

	private void write(SelectionKey selectionKey) {
		Client client = (Client) selectionKey.attachment();
		PackChannel channel = client.channel;

		try {
			boolean success = channel.writeLastPack();
			if (!success) {
				selectionKey.interestOpsOr(SelectionKey.OP_WRITE);
				selector.wakeup();
			}
		}
		catch (CancelledKeyException e) {
			// bye key
		}
		catch (IOException e) {
			if (!e.getMessage().contains("An existing connection was forcibly closed by the remote host")) {
				throw new SocketConnectionException(
						String.format("An error occurs while write to client %s", client.id), e);
			}

			closeClientConnection(client);
		}
	}

	private void closeClientConnection(Client client) {
		boolean changed = client.isConnected.compareAndSet(true, false);
		if (!changed) {
			return;
		}

		try {
			PackChannel packChannel = client.channel;
			packChannel.close();
		}
		catch (IOException e) {
			new SocketConnectionException(
					String.format("An error occurs while closing channel of client %s", client.id),
					e).printStackTrace();
		}
		finally {
			eventDispatcher.fire(new ClientConnectionClosedEvent(client.id));
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

	private static class Client {
		private final long id;
		private final PackChannel channel;
		private final AtomicBoolean isConnected;

		private Client(long id, PackChannel channel) {
			this.id = id;
			this.channel = channel;
			this.isConnected = new AtomicBoolean(true);
		}
	}
}
