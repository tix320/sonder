package com.github.tix320.sonder.internal.common.rpc.extra.extractor;

import java.lang.reflect.Method;

import com.github.tix320.sonder.api.common.communication.Headers;
import com.github.tix320.sonder.api.common.rpc.extra.ClientID;
import com.github.tix320.sonder.api.common.rpc.extra.ExtraArg;
import com.github.tix320.sonder.api.common.rpc.extra.ExtraParamDefinition;
import com.github.tix320.sonder.api.common.rpc.extra.OriginExtraArgExtractor;
import com.github.tix320.sonder.internal.common.ProtocolOrientation;

/**
 * @author Tigran Sargsyan on 24-Mar-20.
 */
public class OriginMethodClientIdExtractor implements OriginExtraArgExtractor<ClientID> {

	private final ProtocolOrientation protocolOrientation;

	public OriginMethodClientIdExtractor(ProtocolOrientation protocolOrientation) {
		this.protocolOrientation = protocolOrientation;
	}

	@Override
	public ExtraParamDefinition<ClientID, ?> getParamDefinition() {
		switch (protocolOrientation) {
			case SERVER:
				return new ExtraParamDefinition<>(ClientID.class, long.class, true);
			case CLIENT:
				return new ExtraParamDefinition<>(ClientID.class, long.class, false);
			default:
				throw new IllegalStateException();
		}
	}

	@Override
	public Headers extract(ExtraArg<ClientID> extraArg, Method method) {
		return Headers.builder().header(Headers.DESTINATION_ID, extraArg.getValue()).build();
	}
}
