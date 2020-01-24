package com.github.tix320.sonder.api.server;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongFunction;

import com.github.tix320.sonder.api.common.communication.Protocol;
import com.github.tix320.sonder.api.common.communication.Transfer;
import com.github.tix320.sonder.api.common.rpc.Endpoint;
import com.github.tix320.sonder.api.common.rpc.Origin;
import com.github.tix320.sonder.internal.common.util.ClassFinder;
import com.github.tix320.sonder.internal.server.SocketClientsSelector;
import com.github.tix320.sonder.internal.server.rpc.ServerRPCProtocol;
import com.github.tix320.sonder.internal.server.topic.ServerTopicProtocol;

/**
 * Builder for socket server {@link Sonder}.
 */
public final class SonderBuilder {

	private final InetSocketAddress inetSocketAddress;

	private final Map<String, Protocol> protocols;

	private Duration headersTimeoutDuration;

	private LongFunction<Duration> contentTimeoutDurationFactory;

	private ExecutorService workers;

	public SonderBuilder(InetSocketAddress inetSocketAddress) {
		this.inetSocketAddress = inetSocketAddress;
		this.protocols = new HashMap<>();
		this.headersTimeoutDuration = Duration.ofSeconds(Integer.MAX_VALUE);
		this.headersTimeoutDuration = Duration.ofSeconds(5);
		this.contentTimeoutDurationFactory = contentLength -> {
			long timout = Math.max((long) Math.ceil(contentLength * (60D / 1024 / 1024 / 1024)), 1);
			return Duration.ofSeconds(timout);
		};
		this.workers = Executors.newCachedThreadPool();
	}

	/**
	 * Register RPC protocol {@link ServerRPCProtocol} to server.
	 *
	 * @param packagesToScan to find origin interfaces {@link Origin}, and endpoint classes {@link Endpoint}.
	 *
	 * @return self
	 */
	public SonderBuilder withRPCProtocol(String... packagesToScan) {
		List<Class<?>> classes = ClassFinder.getPackageClasses(packagesToScan);
		ServerRPCProtocol protocol = new ServerRPCProtocol(classes);
		protocols.put(protocol.getName(), protocol);
		return this;
	}

	/**
	 * Register topic protocol {@link ServerRPCProtocol} to server.
	 *
	 * @return self
	 */
	public SonderBuilder withTopicProtocol() {
		ServerTopicProtocol protocol = new ServerTopicProtocol();
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
	public SonderBuilder headersTimeoutDuration(Duration duration) {
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
	public SonderBuilder contentTimeoutDurationFactory(LongFunction<Duration> factory) {
		contentTimeoutDurationFactory = factory;
		return this;
	}

	/**
	 * Set maximum count of threads, which will be used for handling clients transfers.
	 * If not set, then {@link Executors#newCachedThreadPool()}  will be used.
	 *
	 * @param count max threads count
	 *
	 * @return self
	 */
	public SonderBuilder workersCount(int count) {
		workers = Executors.newFixedThreadPool(count);
		return this;
	}

	/**
	 * Build and run server.
	 *
	 * @return server instance.
	 */
	public Sonder build() {
		return new Sonder(
				new SocketClientsSelector(inetSocketAddress, headersTimeoutDuration, contentTimeoutDurationFactory,
						workers), protocols);
	}
}
