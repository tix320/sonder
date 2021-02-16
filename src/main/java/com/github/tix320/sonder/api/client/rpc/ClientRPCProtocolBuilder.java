package com.github.tix320.sonder.api.client.rpc;


import com.github.tix320.sonder.internal.common.rpc.RPCProtocolBuilder;
import com.github.tix320.sonder.internal.common.rpc.protocol.RPCProtocolConfig;

/**
 * @author Tigran Sargsyan on 25-Aug-20
 */
public final class ClientRPCProtocolBuilder extends RPCProtocolBuilder<ClientRPCProtocol, ClientRPCProtocolBuilder> {

	@Override
	protected ClientRPCProtocol build(RPCProtocolConfig protocolConfig) {
		return new ClientRPCProtocol(protocolConfig);
	}
}
