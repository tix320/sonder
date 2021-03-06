package com.github.tix320.sonder.api.server;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

import com.github.tix320.sonder.api.common.communication.Protocol;

/**
 * Builder for socket server {@link SonderServer}.
 */
public final class SonderServerBuilder {

	private final InetSocketAddress inetSocketAddress;

	private final Map<String, ServerSideProtocol> protocols;

	public SonderServerBuilder(InetSocketAddress inetSocketAddress) {
		this.inetSocketAddress = inetSocketAddress;
		this.protocols = new HashMap<>();
	}

	/**
	 * Register protocol {@link Protocol}.
	 *
	 * @param protocol to register.
	 *
	 * @return self
	 */
	public SonderServerBuilder registerProtocol(ServerSideProtocol protocol) {
		String protocolName = protocol.getName();
		if (protocols.containsKey(protocolName)) {
			throw new IllegalStateException(String.format("Protocol %s already registered", protocolName));
		}
		protocols.put(protocolName, protocol);

		return this;
	}

	/**
	 * Build server instance.
	 *
	 * @return server instance.
	 */
	public SonderServer build() {
		return new SonderServer(inetSocketAddress, protocols);
	}
}
