package com.github.tix320.sonder.internal.client.rpc;

import java.util.List;

import com.github.tix320.sonder.api.client.communication.ClientSideProtocol;
import com.github.tix320.sonder.api.common.rpc.extra.EndpointExtraArgInjector;
import com.github.tix320.sonder.api.common.rpc.extra.OriginExtraArgExtractor;
import com.github.tix320.sonder.internal.client.rpc.extra.ClientEndpointMethodClientIdInjector;
import com.github.tix320.sonder.internal.client.rpc.extra.ClientOriginMethodClientIdExtractor;
import com.github.tix320.sonder.internal.common.rpc.protocol.ProtocolConfig;
import com.github.tix320.sonder.internal.common.rpc.protocol.RPCProtocol;

/**
 * @author Tigran Sargsyan on 25-Aug-20
 */
public final class ClientRPCProtocol extends RPCProtocol implements ClientSideProtocol {

	public ClientRPCProtocol(ProtocolConfig protocolConfig) {
		super(protocolConfig);
	}

	@Override
	protected void init() {

	}

	@Override
	protected List<OriginExtraArgExtractor<?, ?>> getBuiltInExtractors() {
		return List.of(new ClientOriginMethodClientIdExtractor());
	}

	@Override
	protected List<EndpointExtraArgInjector<?, ?>> getBuiltInInjectors() {
		return List.of(new ClientEndpointMethodClientIdInjector());
	}
}
