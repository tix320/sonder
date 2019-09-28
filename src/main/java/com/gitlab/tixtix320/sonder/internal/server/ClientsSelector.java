package com.gitlab.tixtix320.sonder.internal.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.gitlab.tixtix320.kiwi.api.observable.Observable;
import com.gitlab.tixtix320.kiwi.api.observable.subject.Subject;
import com.gitlab.tixtix320.kiwi.api.util.IDGenerator;
import com.gitlab.tixtix320.sonder.internal.common.ByteUtils;
import com.gitlab.tixtix320.sonder.internal.common.SocketConnection;

public final class ClientsSelector {

	private final Selector selector;

	private final Subject<byte[]> requests;

	private final Map<Long, SocketConnection> connections;

	private final Map<Long, Queue<byte[]>> messageQueues;

	private final IDGenerator clientIdGenerator;

	public ClientsSelector(InetSocketAddress address) {
		this.requests = Subject.single();
		this.connections = new ConcurrentHashMap<>();
		this.messageQueues = new ConcurrentHashMap<>();
		this.clientIdGenerator = new IDGenerator(1);
		try {
			this.selector = Selector.open();
		}
		catch (IOException e) {
			throw new RuntimeException();
		}

		CompletableFuture.runAsync(() -> {
			try {
				ServerSocketChannel serverChannel = ServerSocketChannel.open();
				serverChannel.bind(address);
				serverChannel.configureBlocking(false);
				serverChannel.register(selector, SelectionKey.OP_ACCEPT);
				//noinspection InfiniteLoopStatement
				while (true) {
					selector.select();
					Set<SelectionKey> selectedKeys = selector.selectedKeys();
					Iterator<SelectionKey> iterator = selectedKeys.iterator();
					while (iterator.hasNext()) {
						SelectionKey selectionKey = iterator.next();
						if (selectionKey.isAcceptable()) {
							SocketChannel clientChannel = serverChannel.accept();
							clientChannel.configureBlocking(false);
							long connectedClientID = clientIdGenerator.next();

							clientChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE,
									connectedClientID);

							SocketConnection socketConnection = new SocketConnection(clientChannel);
							socketConnection.requests().subscribe(requests::next);
							connections.put(connectedClientID, socketConnection);

							ConcurrentLinkedQueue<byte[]> queue = new ConcurrentLinkedQueue<>();

							queue.add(ByteUtils.longToBytes(connectedClientID));
							messageQueues.put(connectedClientID, queue);
						}
						else if (selectionKey.isReadable()) {
							Long clientId = (Long) selectionKey.attachment();

							SocketConnection connection = connections.get(clientId);
							connection.read();
						}
						else if (selectionKey.isWritable()) {
							Long clientId = (Long) selectionKey.attachment();
							SocketConnection connection = connections.get(clientId);

							Queue<byte[]> queue = messageQueues.get(clientId);

							byte[] data = queue.poll();
							if (data != null) {
								connection.write(data);
							}
						}
						else {
							throw new IllegalStateException();
						}
						iterator.remove();
					}
				}
			}
			catch (IOException e) {
				throw new RuntimeException(e);
			}
		}).exceptionally(throwable -> {
			throwable.getCause().printStackTrace();
			return null;
		});

	}

	public Observable<byte[]> requests() {
		return requests.asObservable();
	}

	public void send(long clientId, byte[] data) {
		Queue<byte[]> queue = messageQueues.get(clientId);
		if (queue == null) {
			throw new IllegalArgumentException(String.format("Client by id %s not found", clientId));
		}
		queue.add(data);
	}

	public void close() throws IOException {
		selector.close();
	}
}
