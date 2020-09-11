package com.github.tix320.sonder.api.client;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongFunction;

import com.github.tix320.sonder.api.common.communication.Protocol;
import com.github.tix320.sonder.api.common.communication.Transfer;
import com.github.tix320.sonder.internal.client.SocketServerConnection;
import com.github.tix320.sonder.internal.common.event.EventDispatcher;
import com.github.tix320.sonder.internal.common.event.SimpleEventDispatcher;

/**
 * Builder for socket client {@link SonderClient}.
 */
public final class SonderClientBuilder {

	private final InetSocketAddress inetSocketAddress;

	private final Map<String, Protocol> protocols;

	private LongFunction<Duration> contentTimeoutDurationFactory;

	private final EventDispatcher sonderEventDispatcher = new SimpleEventDispatcher();

	SonderClientBuilder(InetSocketAddress inetSocketAddress) {
		if (inetSocketAddress.getAddress().isAnyLocalAddress()) {
			throw new IllegalArgumentException("Please specify host");
		}
		this.inetSocketAddress = inetSocketAddress;
		this.protocols = new HashMap<>();
		this.contentTimeoutDurationFactory = contentLength -> {
			long timout = Math.max((long) Math.ceil(contentLength * (60D / 1024 / 1024 / 1024)), 1);
			return Duration.ofSeconds(timout);
		};
	}

	/**
	 * Register protocol {@link Protocol}.
	 *
	 * @param protocol to register.
	 *
	 * @return self
	 */
	public SonderClientBuilder registerProtocol(Protocol protocol) {
		String protocolName = protocol.getName();
		if (protocols.containsKey(protocolName)) {
			throw new IllegalStateException(String.format("Protocol %s already registered", protocolName));
		}
		protocols.put(protocolName, protocol);

		return this;
	}

	/**
	 * Set timeout factory for transfer {@link Transfer} content receiving.
	 * If content will not fully received in this duration, then transferring will be reset.
	 *
	 * @param factory to create timeout for every transfer content. ('contentLength' to 'timeout')
	 *
	 * @return self
	 *
	 * @see Transfer
	 */
	public SonderClientBuilder contentTimeoutDurationFactory(LongFunction<Duration> factory) {
		contentTimeoutDurationFactory = factory;
		return this;
	}

	/**
	 * Build and run client.
	 *
	 * @return client instance.
	 */
	public SonderClient build() {
		return new SonderClient(
				new SocketServerConnection(inetSocketAddress, contentTimeoutDurationFactory, sonderEventDispatcher),
				protocols, sonderEventDispatcher);
	}
}
