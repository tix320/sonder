package com.github.tix320.sonder.internal.server.rpc;

import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

import com.github.tix320.kiwi.api.reactive.observable.Subscription;
import com.github.tix320.kiwi.api.util.CantorPair;
import com.github.tix320.sonder.api.common.rpc.extra.EndpointExtraArgInjector;
import com.github.tix320.sonder.api.common.rpc.extra.OriginExtraArgExtractor;
import com.github.tix320.sonder.api.server.communication.ServerSideProtocol;
import com.github.tix320.sonder.api.server.event.ClientConnectionClosedEvent;
import com.github.tix320.sonder.internal.common.rpc.protocol.ProtocolConfig;
import com.github.tix320.sonder.internal.common.rpc.protocol.RPCProtocol;
import com.github.tix320.sonder.internal.server.rpc.extra.ServerEndpointMethodClientIdInjector;
import com.github.tix320.sonder.internal.server.rpc.extra.ServerOriginMethodClientIdExtractor;

/**
 * @author Tigran Sargsyan on 25-Aug-20
 */
public final class ServerRPCProtocol extends RPCProtocol implements ServerSideProtocol {

	public ServerRPCProtocol(ProtocolConfig protocolConfig) {
		super(protocolConfig);
	}

	@Override
	protected void init() {
		listenConnectionCloses();
	}

	@Override
	protected List<OriginExtraArgExtractor<?, ?>> getBuiltInExtractors() {
		return List.of(new ServerOriginMethodClientIdExtractor());
	}

	@Override
	protected List<EndpointExtraArgInjector<?, ?>> getBuiltInInjectors() {
		return List.of(new ServerEndpointMethodClientIdInjector());
	}

	private void listenConnectionCloses() {
		this.sonderEventDispatcher.on(ClientConnectionClosedEvent.class)
				.subscribe(event -> cleanupSubscriptionsOfClient(event.getClientId()));
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
}
