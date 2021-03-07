package com.github.tix320.sonder.api.server.rpc;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.github.tix320.kiwi.api.reactive.observable.Subscription;
import com.github.tix320.sonder.api.common.communication.ChannelTransfer;
import com.github.tix320.sonder.api.common.communication.Headers;
import com.github.tix320.sonder.api.common.communication.Transfer;
import com.github.tix320.sonder.api.server.ServerSideProtocol;
import com.github.tix320.sonder.api.server.TransferTunnel;
import com.github.tix320.sonder.api.server.event.ServerEvents;
import com.github.tix320.sonder.internal.common.rpc.protocol.RPCProtocol;
import com.github.tix320.sonder.internal.common.rpc.protocol.RPCProtocolConfig;
import com.github.tix320.sonder.internal.server.rpc.extra.ServerEndpointMethodClientIdInjector;
import com.github.tix320.sonder.internal.server.rpc.extra.ServerOriginMethodClientIdExtractor;

/**
 * @author Tigran Sargsyan on 25-Aug-20
 */
public final class ServerRPCProtocol extends RPCProtocol implements ServerSideProtocol {

	private final Map<Long, Map<Long, Subscription>> realSubscriptions; // <clientId, <responseKey, Subscription>>

	private TransferTunnel transferTunnel;

	ServerRPCProtocol(RPCProtocolConfig protocolConfig) {
		super(protocolConfig.add(List.of(new ServerOriginMethodClientIdExtractor()),
				List.of(new ServerEndpointMethodClientIdInjector())));
		this.realSubscriptions = new ConcurrentHashMap<>();
	}

	@Override
	public void init(TransferTunnel transferTunnel, ServerEvents serverEvents) {
		synchronized (this) { // also for memory effects
			this.transferTunnel = transferTunnel;
			serverEvents.deadConnections().subscribe(client -> cleanupSubscriptionsOfClient(client.getId()));
		}
	}

	@Override
	public void handleIncomingTransfer(long clientId, Transfer transfer) throws IOException {
		TransferSender transferSender = responseTransfer -> transferTunnel.send(clientId, responseTransfer);

		Map<Long, Subscription> clientSubscriptions = realSubscriptions.computeIfAbsent(clientId,
				aLong -> new ConcurrentHashMap<>());

		SubscriptionAdder subscriptionAdder = clientSubscriptions::put;
		SubscriptionRemover subscriptionRemover = clientSubscriptions::remove;

		Headers headers = transfer.headers();
		headers = headers.compose().header(ServerRPCHeaders.SOURCE_CLIENT_ID, clientId).build();
		transfer = new ChannelTransfer(headers, transfer.contentChannel());

		handleIncomingTransfer(transfer, transferSender, subscriptionAdder, subscriptionRemover);
	}

	@Override
	protected TransferSender getTransferSenderFromOriginCallHeaders(Headers extraArgHeaders) {
		long clientId = extraArgHeaders.getNonNullLong(ServerRPCHeaders.DESTINATION_CLIENT_ID);

		return transfer -> transferTunnel.send(clientId, transfer);
	}

	private void cleanupSubscriptionsOfClient(long clientIdForDelete) {
		Map<Long, Subscription> clientSubscriptions = realSubscriptions.remove(clientIdForDelete);
		if (clientSubscriptions != null) {
			clientSubscriptions.clear();
		}
	}

	public static final class ServerRPCHeaders {
		public static final String DESTINATION_CLIENT_ID = "destination-client-id";
		public static final String SOURCE_CLIENT_ID = "source-client-id";
	}
}
