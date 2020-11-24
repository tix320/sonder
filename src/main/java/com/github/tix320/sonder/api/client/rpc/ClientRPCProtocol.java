package com.github.tix320.sonder.api.client.rpc;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.github.tix320.kiwi.api.reactive.observable.Subscription;
import com.github.tix320.sonder.api.client.ClientSideProtocol;
import com.github.tix320.sonder.api.client.TransferTunnel;
import com.github.tix320.sonder.api.common.communication.Headers;
import com.github.tix320.sonder.api.common.communication.Transfer;
import com.github.tix320.sonder.api.common.event.EventListener;
import com.github.tix320.sonder.internal.common.rpc.protocol.ProtocolConfig;
import com.github.tix320.sonder.internal.common.rpc.protocol.RPCProtocol;
import com.github.tix320.sonder.internal.common.rpc.protocol.RemoteSubscriptionPublisher;

/**
 * @author Tigran Sargsyan on 25-Aug-20
 */
public final class ClientRPCProtocol extends RPCProtocol implements ClientSideProtocol {

	private final Map<Long, Subscription> realSubscriptions; // `responseKey` in key

	private TransferTunnel transferTunnel;

	ClientRPCProtocol(ProtocolConfig protocolConfig) {
		super(protocolConfig);
		this.realSubscriptions = new ConcurrentHashMap<>();
	}

	@Override
	public void init(TransferTunnel transferTunnel, EventListener eventListener) {
		synchronized (this) { // also for memory effects
			this.transferTunnel = transferTunnel;
		}
	}

	@Override
	public void reset() {
		synchronized (this) {
			requestMetadataByResponseKey.values()
					.forEach(requestMetadata -> requestMetadata.getResponsePublisher().complete());
			requestMetadataByResponseKey.clear();

			remoteSubscriptionPublishers.values().forEach(RemoteSubscriptionPublisher::closePublisher);
			remoteSubscriptionPublishers.clear();
			this.transferTunnel = null;
		}
	}

	@Override
	public void handleIncomingTransfer(Transfer transfer) throws IOException {
		TransferSender transferSender = transferTunnel::send;
		SubscriptionAdder subscriptionAdder = realSubscriptions::put;
		SubscriptionRemover subscriptionRemover = realSubscriptions::remove;

		handleIncomingTransfer(transfer, transferSender, subscriptionAdder, subscriptionRemover);
	}

	@Override
	protected TransferSender getTransferSenderFromOriginCallHeaders(Headers extraArgHeaders) {
		return transferTunnel::send;
	}
}
