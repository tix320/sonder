package com.github.tix320.sonder.api.client;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;

import com.github.tix320.sonder.api.common.communication.Protocol;
import com.github.tix320.sonder.internal.client.SocketServerConnection;
import com.github.tix320.sonder.internal.client.rpc.ClientRPCProtocol;
import com.github.tix320.sonder.internal.client.topic.ClientTopicProtocol;
import com.github.tix320.sonder.internal.common.util.ClassFinder;

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

	public ClonderBuilder withRPCProtocol(String... packagesToScan) {
		List<Class<?>> classes = ClassFinder.getPackageClasses(packagesToScan);
		ClientRPCProtocol protocol = new ClientRPCProtocol(classes);
		protocols.put(protocol.getName(), protocol);
		return this;
	}

	public ClonderBuilder withTopicProtocol() {
		ClientTopicProtocol protocol = new ClientTopicProtocol();
		protocols.put(protocol.getName(), protocol);
		return this;
	}

	public ClonderBuilder headersTimeoutDuration(Duration duration) {
		headersTimeoutDuration = duration;
		return this;
	}

	public ClonderBuilder contentTimeoutDurationFactory(LongFunction<Duration> factory) {
		contentTimeoutDurationFactory = factory;
		return this;
	}

	public Clonder build() {
		return new Clonder(
				new SocketServerConnection(inetSocketAddress, headersTimeoutDuration, contentTimeoutDurationFactory),
				protocols);
	}
}
