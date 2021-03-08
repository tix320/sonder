package com.github.tix320.sonder.api.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.tix320.skimp.api.exception.ExceptionUtils;
import com.github.tix320.skimp.api.interval.Interval;
import com.github.tix320.skimp.api.interval.IntervalRepeater;
import com.github.tix320.skimp.api.object.None;
import com.github.tix320.sonder.api.client.event.Events;
import com.github.tix320.sonder.api.client.rpc.ClientRPCProtocol;
import com.github.tix320.sonder.api.common.communication.Headers;
import com.github.tix320.sonder.api.common.communication.Protocol;
import com.github.tix320.sonder.api.common.communication.Transfer;
import com.github.tix320.sonder.internal.client.SocketServerConnection;
import com.github.tix320.sonder.internal.common.SonderSide;
import com.github.tix320.sonder.internal.common.SonderSideState;
import com.github.tix320.sonder.internal.common.communication.Pack;

/**
 * Entry point class for your socket client.
 * Provides main functionality for communicating with server.
 *
 * Communication is performed by sending and receiving transfer objects {@link Transfer}.
 * Each transfer is handled by some protocol {@link Protocol}, which will be selected by header of transfer {@link Headers#PROTOCOL}.
 *
 * You can register any protocol by calling method {@link SonderClientBuilder#registerProtocol(ClientSideProtocol)}.
 * There are some built-in protocols, such as RPC protocol {@link ClientRPCProtocol}.
 *
 * Create client builder by calling method {@link #forAddress}.
 *
 * @author tix320 on 20-Dec-18
 * @see Protocol
 * @see Transfer
 */
public final class SonderClient extends SonderSide<ClientSideProtocol> {

	private final SocketServerConnection connection;

	private final Events events;

	private final Interval connectInterval;

	/**
	 * Prepare client creating for this socket address.
	 *
	 * @param inetSocketAddress socket address to connect.
	 *
	 * @return builder for future configuring.
	 */
	public static SonderClientBuilder forAddress(InetSocketAddress inetSocketAddress) {
		return new SonderClientBuilder(inetSocketAddress);
	}

	SonderClient(InetSocketAddress address, Map<String, ClientSideProtocol> protocols, Interval connectInterval) {
		super(protocols);
		this.connection = new SocketServerConnection(address, this::handlePack);

		this.connectInterval = connectInterval;
		this.events = connection::state;
	}

	public void start() throws IOException {
		boolean changed = state.compareAndSet(SonderSideState.INITIAL, SonderSideState.RUNNING);
		if (!changed) {
			throw new IllegalStateException("Already started");
		}

		protocols().forEach(protocol -> protocol.init(new TransferTunnelImpl(protocol.getName()), events));
		if (this.connectInterval == null) {
			connection.connect();

		} else {
			connection.state().conditionalSubscribe(connectionState -> {
				switch (connectionState) {
					case IDLE:
						protocols().forEach(ClientSideProtocol::reset);
						while (true) {
							Interval interval = this.connectInterval.copyByInitialState();
							boolean connected = tryConnect(interval);
							if (connected) {
								break;
							}
						}
						break;
					case CONNECTED:
						System.out.println("SONDER: Connected.");
						break;
					case CLOSED:
						protocols().forEach(ClientSideProtocol::reset);
						System.out.println("SONDER: Connection closed.");
						return false;
				}

				return true;
			});

		}
	}

	public void stop() {
		boolean changed = state.compareAndSet(SonderSideState.RUNNING, SonderSideState.CLOSED);

		if (changed) {
			try {
				connection.close();
			} catch (IOException ignored) {

			}
		}
	}

	public Events events() {
		return events;
	}

	private void handlePack(Pack pack) {
		try {
			Transfer transfer = convertPackToTransfer(pack);
			ClientSideProtocol protocol = findProtocol(transfer.headers());
			protocol.handleIncomingTransfer(transfer);
		} catch (Throwable e) {
			try {
				pack.contentChannel().close();
			} catch (IOException ignored) {
			}
			ExceptionUtils.applyToUncaughtExceptionHandler(e);
		}
	}

	private boolean tryConnect(Interval interval) {
		AtomicInteger count = new AtomicInteger(1);
		IntervalRepeater<None> repeater = IntervalRepeater.of(interval, () -> {
			System.out.printf("SONDER: Try connect %s...%n", count.getAndIncrement());
			try {
				connection.connect();
			} catch (IOException e) {
				System.err.println("SONDER: Connection fail.");
				throw e;
			}

		});

		return repeater.doUntilSuccess(5).isPresent();
	}

	private final class TransferTunnelImpl implements TransferTunnel {

		private final String protocolName;

		private TransferTunnelImpl(String protocolName) {
			this.protocolName = protocolName;
		}

		@Override
		public void send(Transfer transfer) {
			transfer = setProtocolHeader(transfer, protocolName);
			Pack pack = convertTransferToPack(transfer);
			connection.send(pack);
		}
	}
}
