package com.github.tix320.sonder.api.client;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

import com.github.tix320.skimp.api.interval.Interval;
import com.github.tix320.sonder.api.common.communication.Protocol;

/**
 * Builder for socket client {@link SonderClient}.
 */
public final class SonderClientBuilder {

	private final InetSocketAddress inetSocketAddress;

	private final Map<String, ClientSideProtocol> protocols;

	private Interval connectInterval;

	SonderClientBuilder(InetSocketAddress inetSocketAddress) {
		if (inetSocketAddress.getAddress().isAnyLocalAddress()) {
			throw new IllegalArgumentException("Please specify host");
		}
		this.inetSocketAddress = inetSocketAddress;
		this.protocols = new HashMap<>();
		this.connectInterval = null;
	}

	/**
	 * Register protocol {@link Protocol}.
	 *
	 * @param protocol to register.
	 *
	 * @return self
	 */
	public SonderClientBuilder registerProtocol(ClientSideProtocol protocol) {
		String protocolName = protocol.getName();
		if (protocols.containsKey(protocolName)) {
			throw new IllegalStateException(String.format("Protocol %s already registered", protocolName));
		}
		protocols.put(protocolName, protocol);

		return this;
	}

	/**
	 * Set auto reconnect ability.
	 *
	 * @param connectInterval interval for connecting/reconnecting.
	 *
	 * @return self
	 */
	public SonderClientBuilder autoReconnect(Interval connectInterval) {
		this.connectInterval = connectInterval;
		return this;
	}

	/**
	 * Build and run client.
	 *
	 * @return client instance.
	 */
	public SonderClient build() {
		return new SonderClient(inetSocketAddress, protocols, connectInterval);
	}
}
