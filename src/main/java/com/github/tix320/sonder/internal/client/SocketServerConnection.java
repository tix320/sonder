package com.github.tix320.sonder.internal.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.function.LongFunction;

import com.github.tix320.kiwi.api.observable.Observable;
import com.github.tix320.sonder.internal.common.communication.Pack;
import com.github.tix320.sonder.internal.common.communication.PackChannel;
import com.github.tix320.sonder.internal.common.communication.SocketConnectionException;

public class SocketServerConnection implements ServerConnection {

	private final PackChannel channel;

	public SocketServerConnection(InetSocketAddress address, Duration headersTimeoutDuration,
								  LongFunction<Duration> contentTimeoutDurationFactory) {
		try {
			channel = new PackChannel(SocketChannel.open(address), headersTimeoutDuration,
					contentTimeoutDurationFactory);
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
		start();
	}

	@Override
	public Observable<Pack> incomingRequests() {
		return channel.packs();
	}

	@Override
	public void send(Pack pack) {
		try {
			channel.write(pack);
		}
		catch (ClosedChannelException e) {
			throw new SocketConnectionException("Socket connection is closed", e);
		}
		catch (IOException e) {
			try {
				channel.close();
				throw new SocketConnectionException("The problem is occurred while sending data", e);
			}
			catch (IOException ex) {
				e.printStackTrace();
				throw new SocketConnectionException("The problem is occurred while closing socket", ex);
			}
		}
	}

	@Override
	public void close()
			throws IOException {
		channel.close();
	}

	private void start() {
		new Thread(() -> {
			while (true) {
				try {
					channel.read();
				}
				catch (ClosedChannelException e) {
					throw new SocketConnectionException("Socket connection is closed", e);
				}
				catch (IOException e) {
					try {
						channel.close();
						throw new SocketConnectionException("The problem is occurred while reading data", e);
					}
					catch (IOException ex) {
						e.printStackTrace();
						throw new SocketConnectionException("The problem is occurred while closing socket", ex);
					}
				}
				catch (Exception e) {
					e.printStackTrace();
				}
			}
		}).start();

	}
}
