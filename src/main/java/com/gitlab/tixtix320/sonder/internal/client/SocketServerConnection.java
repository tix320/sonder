package com.gitlab.tixtix320.sonder.internal.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;

import com.gitlab.tixtix320.kiwi.api.observable.Observable;
import com.gitlab.tixtix320.sonder.internal.common.communication.PackChannel;

public class SocketServerConnection implements ServerConnection {

	private final PackChannel channel;

	public SocketServerConnection(InetSocketAddress address) {
		try {
			channel = new PackChannel(SocketChannel.open(address));
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
		start();
	}

	@Override
	public Observable<byte[]> requests() {
		return channel.packs();
	}

	@Override
	public void send(byte[] data) {
		try {
			channel.write(data);
		}
		catch (IOException e) {
			if (channel.isOpen()) {
				try {
					channel.close();
				}
				catch (IOException ex) {
					throw new RuntimeException("Cannot send data to server", e);
				}
			}
		}
	}

	@Override
	public void close() throws IOException {
		channel.close();
	}

	private void start() {
		new Thread(() -> {
			while (true) {
				try {
					channel.read();
				}
				catch (IOException e) {
					if (channel.isOpen()) {
						throw new RuntimeException("See cause", e);
					}
					else {
						e.printStackTrace();
						try {
							channel.close();
						}
						catch (IOException ex) {
							ex.printStackTrace();
						}
						break;
					}
				}
				catch (Exception e) {
					e.printStackTrace();
				}
			}
		}).start();

	}
}
