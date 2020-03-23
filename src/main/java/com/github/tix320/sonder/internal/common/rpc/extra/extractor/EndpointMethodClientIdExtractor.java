package com.github.tix320.sonder.internal.common.rpc.extra.extractor;

import java.lang.reflect.Method;

import com.github.tix320.sonder.api.common.communication.Headers;
import com.github.tix320.sonder.api.common.rpc.extra.ClientID;
import com.github.tix320.sonder.api.common.rpc.extra.EndpointExtraArgExtractor;
import com.github.tix320.sonder.api.common.rpc.extra.ExtraParamDefinition;
import com.github.tix320.sonder.internal.common.ProtocolOrientation;

/**
 * @author Tigran Sargsyan on 23-Mar-20.
 */
public class EndpointMethodClientIdExtractor implements EndpointExtraArgExtractor<ClientID, Long> {

	private final ProtocolOrientation protocolOrientation;

	public EndpointMethodClientIdExtractor(ProtocolOrientation protocolOrientation) {
		this.protocolOrientation = protocolOrientation;
	}

	@Override
	public ExtraParamDefinition<ClientID, Long> getParamDefinition() {
		switch (protocolOrientation) {
			case SERVER:
				return new ExtraParamDefinition<>(ClientID.class, long.class, false);
			case CLIENT:
				return new ExtraParamDefinition<>(ClientID.class, Long.class, false);
			default:
				throw new IllegalStateException();
		}
	}

	@Override
	public Long extract(ClientID annotation, Headers headers, Method method) {
		return headers.getLong(Headers.SOURCE_ID);
	}
}
