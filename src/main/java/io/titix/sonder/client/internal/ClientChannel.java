package io.titix.sonder.client.internal;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.gitlab.tixtix320.kiwi.observable.Observable;
import com.gitlab.tixtix320.kiwi.observable.subject.Subject;
import io.titix.sonder.internal.ByteUtils;
import io.titix.sonder.internal.SocketConnection;

public class ClientChannel {

	private final long id;

	private final SocketConnection connection;

	private final Subject<byte[]> requests;

	public ClientChannel(InetSocketAddress address) {
		try {
			connection = new SocketConnection(SocketChannel.open(address));
			start();
			byte[] bytes = connection.requests().get();
			this.id = ByteUtils.bytesToLong(bytes);
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
		this.requests = Subject.single();
		connection.requests().subscribe(requests::next);
	}

	public Observable<byte[]> requests() {
		return requests.asObservable();
	}

	public void send(byte[] data) {
		connection.write(data);
	}

	public long getId() {
		return id;
	}

	public void close() {
		connection.close();
	}

	private void start() {
		CompletableFuture.runAsync(() -> {
			while (true) {
				connection.read();
			}
		})
				.whenCompleteAsync((v, throwable) -> Optional.ofNullable(throwable)
						.ifPresent(t -> t.getCause().printStackTrace()));

	}
}
