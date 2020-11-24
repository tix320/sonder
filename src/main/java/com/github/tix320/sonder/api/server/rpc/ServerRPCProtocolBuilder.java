package com.github.tix320.sonder.api.server.rpc;

import com.github.tix320.sonder.internal.common.rpc.RPCProtocolBuilder;
import com.github.tix320.sonder.internal.common.rpc.protocol.ProtocolConfig;

/**
 * @author Tigran Sargsyan on 25-Aug-20
 */
public final class ServerRPCProtocolBuilder extends RPCProtocolBuilder<ServerRPCProtocol, ServerRPCProtocolBuilder> {

	@Override
	protected ServerRPCProtocol build(ProtocolConfig protocolConfig) {
		return new ServerRPCProtocol(protocolConfig);
	}
}
