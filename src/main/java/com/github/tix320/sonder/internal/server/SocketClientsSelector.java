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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongFunction;

import com.github.tix320.kiwi.api.check.Try;
import com.github.tix320.kiwi.api.function.CheckedRunnable;
import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.kiwi.api.reactive.publisher.Publisher;
import com.github.tix320.kiwi.api.util.IDGenerator;
import com.github.tix320.sonder.api.server.event.ClientConnectionClosedEvent;
import com.github.tix320.sonder.api.server.event.NewClientConnectionEvent;
import com.github.tix320.sonder.api.server.event.SonderServerEvent;
import com.github.tix320.sonder.internal.common.communication.InvalidPackException;
import com.github.tix320.sonder.internal.common.communication.Pack;
import com.github.tix320.sonder.internal.common.communication.PackChannel;
import com.github.tix320.sonder.internal.common.communication.SocketConnectionException;
import com.github.tix320.sonder.internal.common.util.Threads;
import com.github.tix320.sonder.internal.event.SonderEventDispatcher;

public final class SocketClientsSelector implements ClientsSelector {

	private final ExecutorService workers;

	private final Selector selector;

	private final ServerSocketChannel serverChannel;

	private final Publisher<ClientPack> incomingRequests;

	private final Map<Long, Client> clientsById;

	private final IDGenerator clientIdGenerator;

	private final Duration headersTimeoutDuration;

	private final LongFunction<Duration> contentTimeoutDurationFactory;

	private final SonderEventDispatcher<SonderServerEvent> eventDispatcher;

	public SocketClientsSelector(InetSocketAddress address, Duration headersTimeoutDuration,
								 LongFunction<Duration> contentTimeoutDurationFactory, ExecutorService workers,
								 SonderEventDispatcher<SonderServerEvent> eventDispatcher) {
		this.selector = Try.supplyOrRethrow(Selector::open);
		this.incomingRequests = Publisher.simple();
		this.clientsById = new ConcurrentHashMap<>();
		this.clientIdGenerator = new IDGenerator(1); // 1 is important aspect, do not touch!
		this.headersTimeoutDuration = headersTimeoutDuration;
		this.contentTimeoutDurationFactory = contentTimeoutDurationFactory;
		this.workers = workers;
		this.eventDispatcher = eventDispatcher;

		try {
			serverChannel = ServerSocketChannel.open();
			serverChannel.bind(address);
			serverChannel.configureBlocking(false);
			serverChannel.register(selector, SelectionKey.OP_ACCEPT);
		}
		catch (IOException e) {
			throw new SocketConnectionException("Cannot open server socket channel", e);
		}

		start();
	}

	@Override
	public Observable<ClientPack> incomingRequests() {
		return incomingRequests.asObservable();
	}

	@Override
	public void send(ClientPack clientPack) {
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
	public void close() throws IOException {
		incomingRequests.complete();
		selector.close();
	}

	private void start() {
		new Thread(() -> {
			while (true) {
				try {
					selector.select();
				}
				catch (IOException e) {
					throw new SocketConnectionException("The problem is occurred in selector work", e);
				}
				Set<SelectionKey> keys = selector.selectedKeys();
				Iterator<SelectionKey> iterator = keys.iterator();
				while (iterator.hasNext()) {
					SelectionKey selectionKey = iterator.next();
					iterator.remove();
					try {
						if (selectionKey.isAcceptable()) {
							accept();
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
					catch (Exception e) {
						e.printStackTrace();
					}
				}

			}
		}).start();
	}

	private void accept() throws IOException {
		SocketChannel clientChannel = serverChannel.accept();
		clientChannel.configureBlocking(false);
		long clientId = clientIdGenerator.next();
		PackChannel packChannel = new PackChannel(clientChannel, headersTimeoutDuration, contentTimeoutDurationFactory);

		Client client = new Client(clientId, packChannel, new ConcurrentLinkedQueue<>(), new ReentrantLock(), true);

		clientChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE, client);

		clientsById.put(clientId, client);
		packChannel.packs().map(pack -> new ClientPack(clientId, pack)).subscribe(incomingRequests::publish);

		Threads.runAsync(() -> eventDispatcher.fire(new NewClientConnectionEvent(clientId)));
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
			Threads.runAsync(() -> eventDispatcher.fire(new ClientConnectionClosedEvent(client.id)));
		}
	}

	private void runAsync(CheckedRunnable checkedRunnable) {
		CompletableFuture.runAsync(() -> {
			try {
				checkedRunnable.run();
			}
			catch (Exception e) {
				throw new SocketConnectionException("An error occurred while socket reading/writing", e);
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
