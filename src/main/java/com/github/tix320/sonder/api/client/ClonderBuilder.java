package com.github.tix320.sonder.api.client;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;

import com.github.tix320.sonder.api.common.communication.Protocol;
import com.github.tix320.sonder.api.common.communication.Transfer;
import com.github.tix320.sonder.api.common.rpc.Endpoint;
import com.github.tix320.sonder.api.common.rpc.Origin;
import com.github.tix320.sonder.internal.client.SocketServerConnection;
import com.github.tix320.sonder.internal.client.rpc.ClientRPCProtocol;
import com.github.tix320.sonder.internal.client.topic.ClientTopicProtocol;
import com.github.tix320.sonder.internal.common.util.ClassFinder;

/**
 * Builder for socket client {@link Clonder}.
 */
public final class ClonderBuilder {

	private final InetSocketAddress inetSocketAddress;

	private final Map<String, Protocol> protocols;

	private Duration headersTimeoutDuration;

	private LongFunction<Duration> contentTimeoutDurationFactory;

	ClonderBuilder(InetSocketAddress inetSocketAddress) {
		if (inetSocketAddress.getAddress().isAnyLocalAddress()) {
			throw new IllegalArgumentException("Please specify host");
		}
		this.inetSocketAddress = inetSocketAddress;
		this.protocols = new HashMap<>();
		this.headersTimeoutDuration = Duration.ofSeconds(5);
		this.contentTimeoutDurationFactory = contentLength -> {
			long timout = Math.max((long) Math.ceil(contentLength * (60D / 1024 / 1024 / 1024)), 1);
			return Duration.ofSeconds(timout);
		};
	}

	/**
	 * Register RPC protocol {@link ClientRPCProtocol} to client.
	 *
	 * @param packagesToScan to find origin interfaces {@link Origin}, and endpoint classes {@link Endpoint}.
	 *
	 * @return self
	 */
	public ClonderBuilder withRPCProtocol(String... packagesToScan) {
		List<Class<?>> classes = ClassFinder.getPackageClasses(packagesToScan);
		ClientRPCProtocol protocol = new ClientRPCProtocol(classes);
		protocols.put(protocol.getName(), protocol);
		return this;
	}

	/**
	 * Register topic protocol {@link ClientTopicProtocol} to client.
	 *
	 * @return self
	 */
	public ClonderBuilder withTopicProtocol() {
		ClientTopicProtocol protocol = new ClientTopicProtocol();
		protocols.put(protocol.getName(), protocol);
		return this;
	}

	/**
	 * Set timeout for transfer {@link Transfer} headers receiving.
	 * If headers will not fully received in this duration, then transferring will be reset.
	 *
	 * @param duration timeout to set
	 *
	 * @return self
	 *
	 * @see Transfer
	 */
	public ClonderBuilder headersTimeoutDuration(Duration duration) {
		headersTimeoutDuration = duration;
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
	public ClonderBuilder contentTimeoutDurationFactory(LongFunction<Duration> factory) {
		contentTimeoutDurationFactory = factory;
		return this;
	}

	/**
	 * Build and run client.
	 *
	 * @return client instance.
	 */
	public Clonder build() {
		return new Clonder(
				new SocketServerConnection(inetSocketAddress, headersTimeoutDuration, contentTimeoutDurationFactory),
				protocols);
	}
}
