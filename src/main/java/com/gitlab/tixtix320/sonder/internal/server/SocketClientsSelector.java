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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.gitlab.tixtix320.kiwi.api.check.Try;
import com.gitlab.tixtix320.kiwi.api.observable.Observable;
import com.gitlab.tixtix320.kiwi.api.observable.subject.Subject;
import com.gitlab.tixtix320.kiwi.api.util.IDGenerator;
import com.gitlab.tixtix320.sonder.internal.common.communication.InvalidPackException;
import com.gitlab.tixtix320.sonder.internal.common.communication.Pack;
import com.gitlab.tixtix320.sonder.internal.common.communication.PackChannel;
import com.gitlab.tixtix320.sonder.internal.common.communication.SocketConnectionException;

public final class SocketClientsSelector implements ClientsSelector {

	private final Selector selector;

	private final ServerSocketChannel serverChannel;

	private final Subject<ClientPack> incomingRequests;

	private final Map<Long, PackChannel> connections;

	private final Map<Long, Queue<Pack>> messageQueues;

	private final IDGenerator clientIdGenerator;

	public SocketClientsSelector(InetSocketAddress address) {
		this.selector = Try.supplyOrRethrow(Selector::open);
		this.incomingRequests = Subject.single();
		this.connections = new ConcurrentHashMap<>();
		this.messageQueues = new ConcurrentHashMap<>();
		this.clientIdGenerator = new IDGenerator(1);

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

		Queue<Pack> queue = messageQueues.get(clientId);
		if (queue == null) {
			throw new IllegalArgumentException(String.format("Client by id %s not found", clientId));
		}
		queue.add(clientPack.getPack());
	}

	@Override
	public void close()
			throws IOException {
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
				Set<SelectionKey> selectedKeys = selector.selectedKeys();
				Iterator<SelectionKey> iterator = selectedKeys.iterator();
				while (iterator.hasNext()) {
					SelectionKey selectionKey = iterator.next();
					try {
						if (selectionKey.isAcceptable()) {
							accept();
						}
						else if (selectionKey.isReadable()) {
							read(selectionKey);
						}
						else if (selectionKey.isWritable()) {
							write(selectionKey);
						}
						else {
							selectionKey.cancel();
						}
					}
					catch (Exception e) {
						e.printStackTrace();
					}
					finally {
						iterator.remove();
					}
				}

			}
		}).start();
	}

	private void accept()
			throws IOException {
		SocketChannel clientChannel = serverChannel.accept();
		clientChannel.configureBlocking(false);
		long connectedClientID = clientIdGenerator.next();

		clientChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE, connectedClientID);

		PackChannel packChannel = new PackChannel(clientChannel);
		connections.put(connectedClientID, packChannel);
		messageQueues.put(connectedClientID, new ConcurrentLinkedQueue<>());
		packChannel.packs().subscribe(pack -> incomingRequests.next(new ClientPack(connectedClientID, pack)));
	}

	private void read(SelectionKey selectionKey)
			throws InvalidPackException, IOException {
		Long clientId = (Long) selectionKey.attachment();

		PackChannel channel = connections.get(clientId);
		try {
			channel.read();
		}
		catch (IOException e) {
			e.printStackTrace();
			connections.remove(clientId);
			if (channel.isOpen()) {
				channel.close();
			}
		}
	}

	private void write(SelectionKey selectionKey)
			throws IOException {
		Long clientId = (Long) selectionKey.attachment();
		PackChannel channel = connections.get(clientId);

		Queue<Pack> queue = messageQueues.get(clientId);

		Pack data = queue.poll();
		if (data != null) {
			try {
				channel.write(data);
			}
			catch (IOException e) {
				e.printStackTrace();
				connections.remove(clientId);
				if (channel.isOpen()) {
					channel.close();
				}
			}
		}
	}
}
