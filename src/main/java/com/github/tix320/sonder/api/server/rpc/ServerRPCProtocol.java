package com.github.tix320.sonder.api.server.rpc;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import com.github.tix320.kiwi.api.reactive.observable.Subscription;
import com.github.tix320.skimp.api.object.CantorPair;
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

	private final Map<CantorPair, Subscription> realSubscriptions; // [clientId, responseKey] in CantorPair

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
		SubscriptionAdder subscriptionAdder = (responseKey, subscription) -> realSubscriptions.put(
				new CantorPair(clientId, responseKey), subscription);
		SubscriptionRemover subscriptionRemover = responseKey -> realSubscriptions.remove(
				new CantorPair(clientId, responseKey));

		Headers headers = transfer.getHeaders();
		headers = headers.compose().header(ServerRPCHeaders.SOURCE_CLIENT_ID, clientId).build();
		transfer = new ChannelTransfer(headers, transfer.channel());

		handleIncomingTransfer(transfer, transferSender, subscriptionAdder, subscriptionRemover);
	}

	@Override
	protected TransferSender getTransferSenderFromOriginCallHeaders(Headers extraArgHeaders) {
		long clientId = extraArgHeaders.getNonNullLong(ServerRPCHeaders.DESTINATION_CLIENT_ID);

		return transfer -> transferTunnel.send(clientId, transfer);
	}

	private void cleanupSubscriptionsOfClient(long clientIdForDelete) {
		Iterator<Entry<CantorPair, Subscription>> iterator = realSubscriptions.entrySet().iterator();
		while (iterator.hasNext()) {
			Entry<CantorPair, Subscription> entry = iterator.next();
			CantorPair cantorPair = entry.getKey();
			long clientId = cantorPair.first();
			if (clientId == clientIdForDelete) {
				Subscription subscription = entry.getValue();
				subscription.unsubscribe();
				iterator.remove();
			}
		}
	}

	public static final class ServerRPCHeaders {
		public static final String DESTINATION_CLIENT_ID = "destination-client-id";
		public static final String SOURCE_CLIENT_ID = "source-client-id";
	}
}
