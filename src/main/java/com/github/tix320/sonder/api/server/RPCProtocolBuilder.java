package com.github.tix320.sonder.api.server;

import java.util.List;

import com.github.tix320.sonder.internal.common.rpc.BaseRPCProtocolBuilder;
import com.github.tix320.sonder.internal.server.rpc.ServerRPCProtocol;

/**
 * Builder for RPC protocol {@link ServerRPCProtocol}.
 */
public final class RPCProtocolBuilder extends BaseRPCProtocolBuilder<RPCProtocolBuilder, ServerRPCProtocol> {

	protected ServerRPCProtocol build() {
		List<Class<?>> classes = resolveClasses();
		return new ServerRPCProtocol(classes, interceptors);
	}

	@Override
	protected RPCProtocolBuilder getThis() {
		return this;
	}
}
