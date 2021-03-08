package com.github.tix320.sonder.api.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;

import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.skimp.api.exception.ExceptionUtils;
import com.github.tix320.sonder.api.common.Client;
import com.github.tix320.sonder.api.common.communication.Headers;
import com.github.tix320.sonder.api.common.communication.Protocol;
import com.github.tix320.sonder.api.common.communication.Transfer;
import com.github.tix320.sonder.api.server.event.Events;
import com.github.tix320.sonder.api.server.rpc.ServerRPCProtocol;
import com.github.tix320.sonder.internal.common.SonderSide;
import com.github.tix320.sonder.internal.common.SonderSideState;
import com.github.tix320.sonder.internal.common.communication.Pack;
import com.github.tix320.sonder.internal.server.SocketClientsSelector;

/**
 * Entry point class for your socket server.
 * Provides main functionality for communicating with clients.
 *
 * Communication is performed by sending and receiving transfer objects {@link Transfer}.
 * Each transfer is handled by some protocol {@link Protocol}, which will be selected by header of transfer {@link Headers#PROTOCOL}.
 *
 * You can register any protocol by calling method {@link SonderServerBuilder#registerProtocol(ServerSideProtocol)}.
 * There are some built-in protocols, such as RPC protocol {@link ServerRPCProtocol}.
 *
 * Create client builder by calling method {@link #forAddress}.
 *
 * @author Tigran.Sargsyan on 11-Dec-18
 * @see Protocol
 * @see Transfer
 */
public final class SonderServer extends SonderSide<ServerSideProtocol> {

	private final SocketClientsSelector clientsSelector;

	private final Events events;

	/**
	 * Prepare server creating for this socket address.
	 *
	 * @param inetSocketAddress socket address to bind.
	 *
	 * @return builder for future configuring.
	 */
	public static SonderServerBuilder forAddress(InetSocketAddress inetSocketAddress) {
		return new SonderServerBuilder(inetSocketAddress);
	}

	SonderServer(InetSocketAddress address, Map<String, ServerSideProtocol> protocols) {
		super(protocols);
		this.clientsSelector = new SocketClientsSelector(address, this::handlePack);
		this.events = new Events() {
			@Override
			public Observable<Client> newConnections() {
				return clientsSelector.newClients();
			}

			@Override
			public Observable<Client> deadConnections() {
				return clientsSelector.deadClients();
			}
		};
	}

	public void start() throws IOException {
		boolean changed = state.compareAndSet(SonderSideState.INITIAL, SonderSideState.RUNNING);
		if (!changed) {
			throw new IllegalStateException("Already started");
		}

		clientsSelector.run();
		protocols().forEach(protocol -> protocol.init(new TransferTunnelImpl(protocol.getName()), events));
	}

	public void stop() {
		boolean changed = state.compareAndSet(SonderSideState.RUNNING, SonderSideState.CLOSED);

		if (changed) {
			try {
				clientsSelector.close();
			} catch (IOException ignored) {

			}
		}
	}

	public Events events() {
		return events;
	}

	private void handlePack(long clientId, Pack pack) {
		try {
			Transfer transfer = convertPackToTransfer(pack);
			ServerSideProtocol protocol = findProtocol(transfer.headers());
			protocol.handleIncomingTransfer(clientId, transfer);
		} catch (Throwable e) {
			try {
				pack.contentChannel().close();
			} catch (IOException ignored) {
			}
			ExceptionUtils.applyToUncaughtExceptionHandler(e);
		}
	}

	private final class TransferTunnelImpl implements TransferTunnel {

		private final String protocolName;

		private TransferTunnelImpl(String protocolName) {
			this.protocolName = protocolName;
		}

		@Override
		public void send(long clientId, Transfer transfer) {
			transfer = setProtocolHeader(transfer, protocolName);
			Pack pack = convertTransferToPack(transfer);
			clientsSelector.send(clientId, pack);
		}
	}
}
