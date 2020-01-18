package com.github.tix320.sonder.api.server;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;

import com.github.tix320.sonder.api.common.communication.Protocol;
import com.github.tix320.sonder.internal.common.util.ClassFinder;
import com.github.tix320.sonder.internal.server.SocketClientsSelector;
import com.github.tix320.sonder.internal.server.rpc.ServerRPCProtocol;
import com.github.tix320.sonder.internal.server.topic.ServerTopicProtocol;

public final class SonderBuilder {

	private final InetSocketAddress inetSocketAddress;

	private final Map<String, Protocol> protocols;

	private Duration headersTimeoutDuration;

	private LongFunction<Duration> contentTimeoutDurationFactory;

	public SonderBuilder(InetSocketAddress inetSocketAddress) {
		this.inetSocketAddress = inetSocketAddress;
		this.protocols = new HashMap<>();
		this.headersTimeoutDuration = Duration.ofSeconds(Integer.MAX_VALUE);
		this.contentTimeoutDurationFactory = contentLength -> {
			// long timout = Math.max((long) Math.ceil(contentLength * (60D / 1024 / 1024 / 1024)), 1);
			return Duration.ofSeconds(Integer.MAX_VALUE);
		};
	}

	public SonderBuilder withRPCProtocol(String... packagesToScan) {
		List<Class<?>> classes = ClassFinder.getPackageClasses(packagesToScan);
		ServerRPCProtocol protocol = new ServerRPCProtocol(classes);
		protocols.put(protocol.getName(), protocol);
		return this;
	}

	public SonderBuilder withTopicProtocol() {
		ServerTopicProtocol protocol = new ServerTopicProtocol();
		protocols.put(protocol.getName(), protocol);
		return this;
	}

	public SonderBuilder headersTimeoutDuration(Duration duration) {
		headersTimeoutDuration = duration;
		return this;
	}

	public SonderBuilder contentTimeoutDurationFactory(LongFunction<Duration> factory) {
		contentTimeoutDurationFactory = factory;
		return this;
	}

	public Sonder build() {
		return new Sonder(
				new SocketClientsSelector(inetSocketAddress, headersTimeoutDuration, contentTimeoutDurationFactory),
				protocols);
	}
}
