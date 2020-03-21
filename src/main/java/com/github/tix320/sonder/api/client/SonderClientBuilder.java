package com.github.tix320.sonder.api.client;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongFunction;

import com.github.tix320.sonder.api.client.event.SonderClientEvent;
import com.github.tix320.sonder.api.common.RPCProtocolBuilder;
import com.github.tix320.sonder.api.common.communication.Protocol;
import com.github.tix320.sonder.api.common.communication.Transfer;
import com.github.tix320.sonder.internal.client.SocketServerConnection;
import com.github.tix320.sonder.internal.client.topic.ClientTopicProtocol;
import com.github.tix320.sonder.internal.common.ProtocolOrientation;
import com.github.tix320.sonder.internal.common.rpc.RPCProtocol;
import com.github.tix320.sonder.internal.event.SimpleEventDispatcher;
import com.github.tix320.sonder.internal.event.SonderEventDispatcher;

/**
 * Builder for socket client {@link SonderClient}.
 */
public final class SonderClientBuilder {

	private final InetSocketAddress inetSocketAddress;

	private final Map<String, Protocol> protocols;

	private Duration headersTimeoutDuration;

	private LongFunction<Duration> contentTimeoutDurationFactory;

	SonderClientBuilder(InetSocketAddress inetSocketAddress) {
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
	 * Register RPC protocol {@link RPCProtocol} to server.
	 *
	 * @param protocolBuilder function for configuring protocol {@link RPCProtocolBuilder}.
	 *
	 * @return self
	 */
	public SonderClientBuilder withRPCProtocol(Consumer<RPCProtocolBuilder> protocolBuilder) {
		RPCProtocolBuilder rpcProtocolBuilder = new RPCProtocolBuilder(ProtocolOrientation.CLIENT);
		protocolBuilder.accept(rpcProtocolBuilder);
		RPCProtocol protocol = rpcProtocolBuilder.build();
		protocols.put(protocol.getName(), protocol);
		return this;
	}

	/**
	 * Register topic protocol {@link ClientTopicProtocol} to client.
	 *
	 * @return self
	 */
	public SonderClientBuilder withTopicProtocol() {
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
	public SonderClientBuilder headersTimeoutDuration(Duration duration) {
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
		SonderEventDispatcher<SonderClientEvent> sonderEventDispatcher = new SimpleEventDispatcher<>();
		return new SonderClient(
				new SocketServerConnection(inetSocketAddress, headersTimeoutDuration, contentTimeoutDurationFactory,
						sonderEventDispatcher), protocols, sonderEventDispatcher);
	}
}
