package com.github.tix320.sonder.api.server;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.LongFunction;

import com.github.tix320.sonder.api.common.RPCProtocolBuilder;
import com.github.tix320.sonder.api.common.communication.Protocol;
import com.github.tix320.sonder.api.common.communication.Transfer;
import com.github.tix320.sonder.api.server.event.SonderServerEvent;
import com.github.tix320.sonder.internal.common.ProtocolOrientation;
import com.github.tix320.sonder.internal.common.rpc.protocol.RPCProtocol;
import com.github.tix320.sonder.internal.event.SimpleEventDispatcher;
import com.github.tix320.sonder.internal.event.SonderEventDispatcher;
import com.github.tix320.sonder.internal.server.SocketClientsSelector;
import com.github.tix320.sonder.internal.server.topic.ServerTopicProtocol;

/**
 * Builder for socket server {@link SonderServer}.
 */
public final class SonderServerBuilder {

	private final InetSocketAddress inetSocketAddress;

	private final Map<String, Protocol> protocols;

	private Duration headersTimeoutDuration;

	private LongFunction<Duration> contentTimeoutDurationFactory;

	private ExecutorService workers;

	private SonderEventDispatcher<SonderServerEvent> sonderEventDispatcher = new SimpleEventDispatcher<>();

	public SonderServerBuilder(InetSocketAddress inetSocketAddress) {
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
	 * Register RPC protocol {@link RPCProtocol} to server.
	 *
	 * @param protocolBuilder function for configuring protocol {@link RPCProtocolBuilder}.
	 *
	 * @return self
	 */
	public SonderServerBuilder withRPCProtocol(Consumer<RPCProtocolBuilder> protocolBuilder) {
		RPCProtocolBuilder rpcProtocolBuilder = new RPCProtocolBuilder(ProtocolOrientation.SERVER,
				sonderEventDispatcher);
		protocolBuilder.accept(rpcProtocolBuilder);
		RPCProtocol protocol = rpcProtocolBuilder.build();
		protocols.put(protocol.getName(), protocol);
		return this;
	}

	/**
	 * Register topic protocol {@link ServerTopicProtocol} to server.
	 *
	 * @return self
	 */
	public SonderServerBuilder withTopicProtocol() {
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
	public SonderServerBuilder headersTimeoutDuration(Duration duration) {
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
	public SonderServerBuilder contentTimeoutDurationFactory(LongFunction<Duration> factory) {
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
	public SonderServerBuilder workersCount(int count) {
		workers = Executors.newFixedThreadPool(count);
		return this;
	}

	/**
	 * Build and run server.
	 *
	 * @return server instance.
	 */
	public SonderServer build() {
		return new SonderServer(
				new SocketClientsSelector(inetSocketAddress, headersTimeoutDuration, contentTimeoutDurationFactory,
						workers, sonderEventDispatcher), protocols, sonderEventDispatcher);
	}
}
