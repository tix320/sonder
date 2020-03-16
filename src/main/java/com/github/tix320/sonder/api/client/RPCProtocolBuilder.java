package com.github.tix320.sonder.api.client;

import java.util.List;

import com.github.tix320.sonder.internal.client.rpc.ClientRPCProtocol;
import com.github.tix320.sonder.internal.common.rpc.BaseRPCProtocolBuilder;
import com.github.tix320.sonder.internal.server.rpc.ServerRPCProtocol;

/**
 * Builder for RPC protocol {@link ServerRPCProtocol}.
 */
public final class RPCProtocolBuilder extends BaseRPCProtocolBuilder<RPCProtocolBuilder, ClientRPCProtocol> {

	protected ClientRPCProtocol build() {
		List<Class<?>> classes = resolveClasses();
		return new ClientRPCProtocol(classes, interceptors);
	}

	@Override
	protected RPCProtocolBuilder getThis() {
		return this;
	}
}
