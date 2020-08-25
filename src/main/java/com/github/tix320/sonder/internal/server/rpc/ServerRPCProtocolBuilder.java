package com.github.tix320.sonder.internal.server.rpc;

import com.github.tix320.sonder.api.common.RPCProtocolBuilder;
import com.github.tix320.sonder.api.server.communication.ServerSideProtocol;

/**
 * @author Tigran Sargsyan on 25-Aug-20
 */
public final class ServerRPCProtocolBuilder extends RPCProtocolBuilder<ServerSideProtocol> {

	@Override
	protected ServerRPCProtocol buildOverride() {
		return new ServerRPCProtocol(getConfigs());
	}
}
