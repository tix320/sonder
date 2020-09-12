package com.github.tix320.sonder.internal.client.rpc.extra;

import java.lang.reflect.Method;

import com.github.tix320.sonder.api.common.communication.Headers;
import com.github.tix320.sonder.api.common.rpc.extra.ClientID;
import com.github.tix320.sonder.api.common.rpc.extra.ExtraParamDefinition;
import com.github.tix320.sonder.api.common.rpc.extra.OriginExtraArgExtractor;

/**
 * @author Tigran Sargsyan on 24-Mar-20.
 */
public final class ClientOriginMethodClientIdExtractor implements OriginExtraArgExtractor<ClientID, Long> {

	@Override
	public ExtraParamDefinition<ClientID, ?> getParamDefinition() {
		return new ExtraParamDefinition<>(ClientID.class, long.class, false);
	}

	@Override
	public Headers extract(Method method, ClientID annotation, Long value) {
		return Headers.builder().header(Headers.DESTINATION_ID, value).build();
	}
}
