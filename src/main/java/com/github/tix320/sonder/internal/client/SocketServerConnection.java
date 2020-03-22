package com.github.tix320.sonder.internal.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.function.LongFunction;

import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.sonder.api.client.event.ConnectionClosedEvent;
import com.github.tix320.sonder.api.client.event.ConnectionEstablishedEvent;
import com.github.tix320.sonder.api.client.event.SonderClientEvent;
import com.github.tix320.sonder.internal.common.communication.Pack;
import com.github.tix320.sonder.internal.common.communication.PackChannel;
import com.github.tix320.sonder.internal.common.communication.SocketConnectionException;
import com.github.tix320.sonder.internal.common.util.Threads;
import com.github.tix320.sonder.internal.event.SonderEventDispatcher;

public class SocketServerConnection implements ServerConnection {

	private final PackChannel channel;

	private final SonderEventDispatcher<SonderClientEvent> eventDispatcher;

	public SocketServerConnection(InetSocketAddress address, Duration headersTimeoutDuration,
								  LongFunction<Duration> contentTimeoutDurationFactory,
								  SonderEventDispatcher<SonderClientEvent> eventDispatcher) {
		this.eventDispatcher = eventDispatcher;
		try {
			this.channel = new PackChannel(SocketChannel.open(address), headersTimeoutDuration,
					contentTimeoutDurationFactory);
			Threads.runAsync(() -> eventDispatcher.fire(new ConnectionEstablishedEvent()));
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
			Threads.runAsync(() -> eventDispatcher.fire(new ConnectionClosedEvent()));
			throw new SocketConnectionException("Socket connection is closed", e);
		}
		catch (IOException e) {
			Threads.runAsync(() -> eventDispatcher.fire(new ConnectionClosedEvent()));
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
					Threads.runAsync(() -> eventDispatcher.fire(new ConnectionClosedEvent()));
					throw new SocketConnectionException("Socket connection is closed", e);
				}
				catch (IOException e) {
					Threads.runAsync(() -> eventDispatcher.fire(new ConnectionClosedEvent()));
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
