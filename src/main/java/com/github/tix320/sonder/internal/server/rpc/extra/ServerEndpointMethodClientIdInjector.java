package com.github.tix320.sonder.internal.server.rpc.extra;

import java.lang.reflect.Method;

import com.github.tix320.sonder.api.common.communication.Headers;
import com.github.tix320.sonder.api.common.rpc.extra.ClientID;
import com.github.tix320.sonder.api.common.rpc.extra.EndpointExtraArgInjector;
import com.github.tix320.sonder.api.common.rpc.extra.ExtraParamDefinition;
import com.github.tix320.sonder.api.server.rpc.ServerRPCProtocol.ServerRPCHeaders;

/**
 * @author Tigran Sargsyan on 23-Mar-20.
 */
public final class ServerEndpointMethodClientIdInjector implements EndpointExtraArgInjector<ClientID, Long> {

	@Override
	public ExtraParamDefinition<ClientID, Long> getParamDefinition() {
		return new ExtraParamDefinition<>(ClientID.class, long.class, false);
	}

	@Override
	public Long extract(Method method, ClientID annotation, Headers headers) {
		return headers.getNonNullLong(ServerRPCHeaders.SOURCE_CLIENT_ID);
	}
}
