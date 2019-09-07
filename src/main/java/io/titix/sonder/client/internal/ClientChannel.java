package io.titix.sonder.client.internal;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.gitlab.tixtix320.kiwi.api.observable.Observable;
import io.titix.sonder.internal.ByteUtils;
import io.titix.sonder.internal.SocketConnection;

public class ClientChannel {

	private final long id;

	private final SocketConnection connection;

	public ClientChannel(InetSocketAddress address) {
		try {
			connection = new SocketConnection(SocketChannel.open(address));
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
		AtomicLong idHolder = new AtomicLong();
		start();
		connection.requests().block().one().subscribe(bytes -> idHolder.set(ByteUtils.bytesToLong(bytes)));
		this.id = idHolder.get();
	}

	public Observable<byte[]> requests() {
		return connection.requests();
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
			try {
				TimeUnit.SECONDS.sleep(1);
			}
			catch (InterruptedException e) {
				e.printStackTrace();
			}
			while (true) {
				connection.read();
			}
		})
				.whenCompleteAsync((v, throwable) -> Optional.ofNullable(throwable)
						.ifPresent(t -> t.getCause().printStackTrace()));

	}
}
