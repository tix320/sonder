package com.github.tix320.sonder.internal.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongFunction;

import com.github.tix320.kiwi.api.check.Try;
import com.github.tix320.kiwi.api.function.CheckedRunnable;
import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.kiwi.api.reactive.property.Property;
import com.github.tix320.kiwi.api.reactive.property.StateProperty;
import com.github.tix320.kiwi.api.reactive.publisher.Publisher;
import com.github.tix320.kiwi.api.util.IDGenerator;
import com.github.tix320.kiwi.api.util.LoopThread;
import com.github.tix320.kiwi.api.util.Threads;
import com.github.tix320.sonder.api.server.event.ClientConnectionClosedEvent;
import com.github.tix320.sonder.api.server.event.NewClientConnectionEvent;
import com.github.tix320.sonder.api.server.event.SonderServerEvent;
import com.github.tix320.sonder.internal.common.State;
import com.github.tix320.sonder.internal.common.communication.InvalidPackException;
import com.github.tix320.sonder.internal.common.communication.Pack;
import com.github.tix320.sonder.internal.common.communication.PackChannel;
import com.github.tix320.sonder.internal.common.communication.SocketConnectionException;
import com.github.tix320.sonder.internal.event.SonderEventDispatcher;

public final class SocketClientsSelector implements ClientsSelector {

	private final InetSocketAddress address;

	private final ExecutorService workers;

	private final Publisher<ClientPack> incomingRequests;

	private final Map<Long, Client> clientsById;

	private final IDGenerator clientIdGenerator;

	private final LongFunction<Duration> contentTimeoutDurationFactory;

	private final SonderEventDispatcher<SonderServerEvent> eventDispatcher;

	private Selector selector;

	private ServerSocketChannel serverChannel;

	private final StateProperty<State> state = Property.forState(State.INITIAL);

	public SocketClientsSelector(InetSocketAddress address, LongFunction<Duration> contentTimeoutDurationFactory,
								 int workersCoreCount, SonderEventDispatcher<SonderServerEvent> eventDispatcher) {
		this.address = address;
		this.incomingRequests = Publisher.simple();
		this.clientsById = new ConcurrentHashMap<>();
		this.clientIdGenerator = new IDGenerator(1); // 1 is important aspect, do not touch!
		this.contentTimeoutDurationFactory = contentTimeoutDurationFactory;
		this.workers = new ThreadPoolExecutor(workersCoreCount, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
				new SynchronousQueue<>(), Threads::daemon);
		this.eventDispatcher = eventDispatcher;
	}

	public synchronized void run() throws IOException {
		boolean changed = state.compareAndSetValue(State.INITIAL, State.RUNNING);
		if (!changed) {
			throw new IllegalStateException("Already runned");
		}

		selector = Try.supplyOrRethrow(Selector::open);
		serverChannel = ServerSocketChannel.open();
		serverChannel.bind(address);
		serverChannel.configureBlocking(false);
		serverChannel.register(selector, SelectionKey.OP_ACCEPT);

		runLoop();
	}

	@Override
	public Observable<ClientPack> incomingRequests() {
		state.checkValue(State.RUNNING);

		return incomingRequests.asObservable();
	}

	@Override
	public void send(ClientPack clientPack) {
		state.checkValue(State.RUNNING);

		long clientId = clientPack.getClientId();

		Client client = clientsById.get(clientId);
		if (client == null) {
			throw new IllegalArgumentException(String.format("Client by id %s not found", clientId));
		}
		if (client.isConnected) {
			Queue<Pack> queue = client.packsForSend;
			queue.add(clientPack.getPack());
		}
		else {
			new IllegalArgumentException(String.format("Client %s is disconnected", clientId)).printStackTrace();
		}
	}

	@Override
	public synchronized void close() throws IOException {
		boolean changed = state.compareAndSetValue(State.RUNNING, State.CLOSED);

		if (changed) {
			incomingRequests.complete();
			selector.close();
		}

	}

	private void runLoop() {
		new LoopThread(() -> {
			try {
				selector.select();
			}
			catch (IOException e) {
				new SocketConnectionException("The problem is occurred in selector work", e).printStackTrace();
				return false;
			}
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
					Client client = (Client) selectionKey.attachment();
					Lock clientLock = client.lock;

					runAsync(() -> {
						if (clientLock.tryLock()) {
							try {
								read(client);
							}
							finally {
								clientLock.unlock();
							}
						}
					});
				}

				else if (selectionKey.isWritable()) {
					Client client = (Client) selectionKey.attachment();
					Lock clientLock = client.lock;
					runAsync(() -> {
						if (clientLock.tryLock()) {
							try {
								write(client);
							}
							finally {
								clientLock.unlock();
							}
						}
					});
				}
				else {
					selectionKey.cancel();
				}
			}

			return true;
		}, false).start();
	}

	private void accept() throws IOException {
		SocketChannel clientChannel = serverChannel.accept();
		clientChannel.configureBlocking(false);
		long clientId = clientIdGenerator.next();
		PackChannel packChannel = new PackChannel(clientChannel, contentTimeoutDurationFactory);

		Client client = new Client(clientId, packChannel, new ConcurrentLinkedQueue<>(), new ReentrantLock(), true);

		clientChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE, client);

		clientsById.put(clientId, client);
		packChannel.packs().map(pack -> new ClientPack(clientId, pack)).subscribe(incomingRequests::publish);

		runAsync(() -> eventDispatcher.fire(new NewClientConnectionEvent(clientId)));
	}

	private void read(Client client) throws InvalidPackException, IOException {
		PackChannel channel = client.channel;
		try {
			channel.read();
		}
		catch (IOException e) {
			System.err.println(String.format(e.getMessage() + ": Client id %s", client.id));
			closeClientConnection(client);
		}
	}

	private void write(Client client) throws IOException {
		PackChannel channel = client.channel;

		Queue<Pack> queue = client.packsForSend;

		Pack data = queue.poll();
		if (data != null) {
			try {
				channel.write(data);
			}
			catch (IOException e) {
				System.err.println(String.format(e.getMessage() + ": Client id %s", client.id));
				closeClientConnection(client);
			}
		}
	}

	private void closeClientConnection(Client client) throws IOException {
		try {
			if (!client.isConnected) {
				return;
			}
			PackChannel packChannel = client.channel;
			client.isConnected = false;
			if (packChannel.isOpen()) {
				packChannel.close();
			}
		}
		finally {
			runAsync(() -> eventDispatcher.fire(new ClientConnectionClosedEvent(client.id)));
		}
	}

	private void runAsync(CheckedRunnable checkedRunnable) {
		CompletableFuture.runAsync(() -> {
			try {
				checkedRunnable.run();
			}
			catch (Exception e) {
				throw new RuntimeException(e);
			}
		}, workers).exceptionally(throwable -> {
			Throwable realException = throwable.getCause();
			realException.printStackTrace();
			return null;
		});
	}

	private static class Client {
		private final long id;
		private final PackChannel channel;
		private final Queue<Pack> packsForSend;
		private final Lock lock;
		private volatile boolean isConnected;

		private Client(long id, PackChannel channel, Queue<Pack> packsForSend, Lock lock, boolean isConnected) {
			this.id = id;
			this.channel = channel;
			this.packsForSend = packsForSend;
			this.lock = lock;
			this.isConnected = isConnected;
		}
	}
}
