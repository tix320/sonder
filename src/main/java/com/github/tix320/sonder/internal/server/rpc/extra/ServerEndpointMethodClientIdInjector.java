package com.github.tix320.sonder.internal.server.rpc.extra;

import java.lang.reflect.Method;

import com.github.tix320.sonder.api.common.communication.Headers;
import com.github.tix320.sonder.api.common.rpc.extra.ClientID;
import com.github.tix320.sonder.api.common.rpc.extra.EndpointExtraArgInjector;
import com.github.tix320.sonder.api.common.rpc.extra.ExtraParamDefinition;

/**
 * @author Tigran Sargsyan on 23-Mar-20.
 */
public class ServerEndpointMethodClientIdInjector implements EndpointExtraArgInjector<ClientID, Long> {

	@Override
	public ExtraParamDefinition<ClientID, Long> getParamDefinition() {
		return new ExtraParamDefinition<>(ClientID.class, long.class, false);
	}

	@Override
	public Long extract(Method method, ClientID annotation, Headers headers) {
		return headers.getLong(Headers.SOURCE_ID);
	}
}
