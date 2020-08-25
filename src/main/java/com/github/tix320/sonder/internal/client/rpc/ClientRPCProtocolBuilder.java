package com.github.tix320.sonder.internal.client.rpc;

import com.github.tix320.sonder.api.client.communication.ClientSideProtocol;
import com.github.tix320.sonder.api.common.RPCProtocolBuilder;

/**
 * @author Tigran Sargsyan on 25-Aug-20
 */
public final class ClientRPCProtocolBuilder extends RPCProtocolBuilder<ClientSideProtocol> {

	@Override
	protected ClientRPCProtocol buildOverride() {
		return new ClientRPCProtocol(getConfigs());
	}
}
