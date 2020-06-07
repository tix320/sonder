package com.github.tix320.sonder.internal.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.*;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongFunction;

import com.github.tix320.kiwi.api.check.Try;
import com.github.tix320.kiwi.api.reactive.property.Property;
import com.github.tix320.kiwi.api.reactive.property.StateProperty;
import com.github.tix320.kiwi.api.util.IDGenerator;
import com.github.tix320.kiwi.api.util.LoopThread;
import com.github.tix320.kiwi.api.util.Threads;
import com.github.tix320.sonder.api.server.event.ClientConnectionClosedEvent;
import com.github.tix320.sonder.api.server.event.NewClientConnectionEvent;
import com.github.tix320.sonder.api.server.event.SonderServerEvent;
import com.github.tix320.sonder.internal.common.State;
import com.github.tix320.sonder.internal.common.communication.InvalidPackException;
import com.github.tix320.sonder.internal.common.communication.PackChannel;
import com.github.tix320.sonder.internal.common.communication.SocketConnectionException;
import com.github.tix320.sonder.internal.event.SonderEventDispatcher;

public final class SocketClientsSelector implements ClientsSelector {

	private final InetSocketAddress address;

	private final ExecutorService workers;

	private final Map<Long, SelectionKey> selectionKeysById;

	private final IDGenerator clientIdGenerator;

	private final LongFunction<Duration> contentTimeoutDurationFactory;

	private final SonderEventDispatcher<SonderServerEvent> eventDispatcher;

	private volatile Selector selector;

	private volatile ServerSocketChannel serverChannel;

	private final StateProperty<State> state = Property.forState(State.INITIAL);

	public SocketClientsSelector(InetSocketAddress address, LongFunction<Duration> contentTimeoutDurationFactory,
								 int workersCoreCount, SonderEventDispatcher<SonderServerEvent> eventDispatcher) {
		this.address = address;
		this.selectionKeysById = new ConcurrentHashMap<>();
		this.clientIdGenerator = new IDGenerator(1); // 1 is important aspect, do not touch!
		this.contentTimeoutDurationFactory = contentTimeoutDurationFactory;
		this.workers = new ThreadPoolExecutor(workersCoreCount, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
				new SynchronousQueue<>(), Threads::daemon);
		this.eventDispatcher = eventDispatcher;
	}

	public void run(Consumer<ClientPack> packConsumer) throws IOException {
		boolean changed = state.compareAndSetValue(State.INITIAL, State.RUNNING);
		if (!changed) {
			throw new IllegalStateException("Already running");
		}

		Selector selector = Try.supplyOrRethrow(Selector::open);
		ServerSocketChannel serverChannel = ServerSocketChannel.open();
		serverChannel.bind(address);
		serverChannel.configureBlocking(false);
		serverChannel.register(selector, SelectionKey.OP_ACCEPT);

		this.selector = selector;
		this.serverChannel = serverChannel;

		runLoop(packConsumer);
	}

	@Override
	public void send(ClientPack clientPack) {
		state.checkValue(State.RUNNING);

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
			catch (IOException e) {
				if (!e.getMessage().contains("An existing connection was forcibly closed by the remote host")) {
					new SocketConnectionException(String.format("An error occurs while write to client %s", client.id),
							e).printStackTrace();
				}

				closeClientConnection(client);
			}
		}
		else {
			new IllegalArgumentException(String.format("Client %s is disconnected", clientId)).printStackTrace();
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
		new LoopThread(() -> {
			try {
				selector.select();

				Set<SelectionKey> keys = selector.selectedKeys();
				Iterator<SelectionKey> iterator = keys.iterator();
				while (iterator.hasNext()) {
					SelectionKey selectionKey = iterator.next();
					iterator.remove();
					if (selectionKey.isAcceptable()) {
						try {
							accept(packConsumer);
						}
						catch (IOException e) {
							e.printStackTrace();
						}
					}
					else if (selectionKey.isReadable()) {
						selectionKey.interestOpsAnd(~SelectionKey.OP_READ);
						runAsync(() -> read(selectionKey));
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
				return false;
			}
			catch (IOException e) {
				new SocketConnectionException("The problem is occurred in selector work", e).printStackTrace();
				return false;
			}

			return true;
		}, false).start();
	}

	private void accept(Consumer<ClientPack> packConsumer) throws IOException {
		SocketChannel clientChannel = serverChannel.accept();
		clientChannel.configureBlocking(false);
		long clientId = clientIdGenerator.next();
		PackChannel packChannel = new PackChannel(clientChannel, contentTimeoutDurationFactory, pack -> {
			ClientPack clientPack = new ClientPack(clientId, pack);
			packConsumer.accept(clientPack);
		});

		Client client = new Client(clientId, packChannel);

		SelectionKey selectionKey = clientChannel.register(selector, SelectionKey.OP_READ, client);

		selectionKeysById.put(clientId, selectionKey);

		runAsync(() -> eventDispatcher.fire(new NewClientConnectionEvent(clientId)));
	}

	private void read(SelectionKey selectionKey) throws InvalidPackException {
		Client client = (Client) selectionKey.attachment();
		PackChannel channel = client.channel;
		try {
			channel.read();
			selectionKey.interestOpsOr(SelectionKey.OP_READ);
			selector.wakeup();
		}
		catch (CancelledKeyException e) {
			// bye-bye key
		}
		catch (IOException e) {
			if (!e.getMessage().contains("An existing connection was forcibly closed by the remote host")) {
				new SocketConnectionException(String.format("An error occurs while write to client %s", client.id),
						e).printStackTrace();
			}

			closeClientConnection(client);
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
				new SocketConnectionException(String.format("An error occurs while write to client %s", client.id),
						e).printStackTrace();
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
			runAsync(() -> eventDispatcher.fire(new ClientConnectionClosedEvent(client.id)));
		}
	}

	private void runAsync(Runnable runnable) {
		try {
			CompletableFuture.runAsync(runnable, workers).exceptionally(throwable -> {
				Throwable realException = throwable.getCause();
				realException.printStackTrace();
				return null;
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
