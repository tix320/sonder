package com.github.tix320.sonder.api.server;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongFunction;

import com.github.tix320.sonder.api.common.communication.Protocol;
import com.github.tix320.sonder.api.common.communication.Transfer;
import com.github.tix320.sonder.internal.common.event.EventDispatcher;
import com.github.tix320.sonder.internal.common.event.SimpleEventDispatcher;
import com.github.tix320.sonder.internal.server.SocketClientsSelector;

/**
 * Builder for socket server {@link SonderServer}.
 */
public final class SonderServerBuilder {

	private final InetSocketAddress inetSocketAddress;

	private final Map<String, Protocol> protocols;

	private LongFunction<Duration> contentTimeoutDurationFactory;

	private int workersCoreCount;

	private final EventDispatcher sonderEventDispatcher = new SimpleEventDispatcher();

	public SonderServerBuilder(InetSocketAddress inetSocketAddress) {
		this.inetSocketAddress = inetSocketAddress;
		this.protocols = new HashMap<>();
		this.contentTimeoutDurationFactory = contentLength -> {
			long timout = Math.max((long) Math.ceil(contentLength * (60D / 1024 / 1024 / 1024)), 1);
			return Duration.ofSeconds(timout);
		};
		this.workersCoreCount = Runtime.getRuntime().availableProcessors();
	}

	/**
	 * Register protocol {@link Protocol}.
	 *
	 * @param protocol to register.
	 */
	public SonderServerBuilder registerProtocol(Protocol protocol) {
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
	public SonderServerBuilder contentTimeoutDurationFactory(LongFunction<Duration> factory) {
		contentTimeoutDurationFactory = factory;
		return this;
	}

	/**
	 * Set core count of threads, which will be used for handling clients transfers.
	 *
	 * @param count core threads count
	 *
	 * @return self
	 */
	public SonderServerBuilder workersCoreCount(int count) {
		workersCoreCount = count;
		return this;
	}

	/**
	 * Build server instance.
	 *
	 * @return server instance.
	 */
	public SonderServer build() {
		return new SonderServer(
				new SocketClientsSelector(inetSocketAddress, contentTimeoutDurationFactory, workersCoreCount,
						sonderEventDispatcher), protocols, sonderEventDispatcher);
	}
}
