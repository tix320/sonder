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
import com.gitlab.tixtix320.sonder.internal.common.communication.PackChannel;
import com.gitlab.tixtix320.sonder.internal.common.communication.SocketConnectionException;

public final class SocketClientsSelector implements ClientsSelector {

	private final Selector selector;

	private final Subject<ClientPack> incomingRequests;

	private final Map<Long, PackChannel> connections;

	private final Map<Long, Queue<byte[]>> messageQueues;

	private final IDGenerator clientIdGenerator;

	public SocketClientsSelector(InetSocketAddress address) {
		this.selector = Try.supplyOrRethrow(Selector::open);
		this.incomingRequests = Subject.single();
		this.connections = new ConcurrentHashMap<>();
		this.messageQueues = new ConcurrentHashMap<>();
		this.clientIdGenerator = new IDGenerator(1);
		start(address);
	}

	@Override
	public Observable<ClientPack> incomingRequests() {
		return incomingRequests.asObservable();
	}

	@Override
	public void send(ClientPack clientPack) {
		long clientId = clientPack.getClientId();

		Queue<byte[]> queue = messageQueues.get(clientId);
		if (queue == null) {
			throw new IllegalArgumentException(String.format("Client by id %s not found", clientId));
		}
		queue.add(clientPack.getData());
	}

	@Override
	public void close()
			throws IOException {
		incomingRequests.complete();
		selector.close();
	}

	private void start(InetSocketAddress address) {
		ServerSocketChannel serverChannel;
		try {
			serverChannel = ServerSocketChannel.open();
			serverChannel.bind(address);
			serverChannel.configureBlocking(false);
			serverChannel.register(selector, SelectionKey.OP_ACCEPT);
		}
		catch (IOException e) {
			throw new SocketConnectionException("Cannot open server socket channel", e);
		}

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
							SocketChannel clientChannel = serverChannel.accept();
							clientChannel.configureBlocking(false);
							long connectedClientID = clientIdGenerator.next();

							clientChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE,
									connectedClientID);

							PackChannel packChannel = new PackChannel(clientChannel);
							packChannel.packs()
									.subscribe(
											bytes -> incomingRequests.next(new ClientPack(connectedClientID, bytes)));
							connections.put(connectedClientID, packChannel);

							ConcurrentLinkedQueue<byte[]> queue = new ConcurrentLinkedQueue<>();

							messageQueues.put(connectedClientID, queue);
						}
						else if (selectionKey.isReadable()) {
							Long clientId = (Long) selectionKey.attachment();

							PackChannel channel = connections.get(clientId);
							try {
								channel.read();
							}
							catch (IOException e) {
								connections.remove(clientId);
								throw e;
							}
						}
						else if (selectionKey.isWritable()) {
							Long clientId = (Long) selectionKey.attachment();
							PackChannel channel = connections.get(clientId);

							Queue<byte[]> queue = messageQueues.get(clientId);

							byte[] data = queue.poll();
							if (data != null) {
								try {
									channel.write(data);
								}
								catch (IOException e) {
									connections.remove(clientId);
									throw e;
								}
							}
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
}
