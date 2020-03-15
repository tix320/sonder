package com.github.tix320.sonder.api.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.github.tix320.kiwi.api.proxy.AnnotationInterceptor;
import com.github.tix320.sonder.api.common.rpc.Endpoint;
import com.github.tix320.sonder.api.common.rpc.Origin;
import com.github.tix320.sonder.internal.client.rpc.ClientRPCProtocol;
import com.github.tix320.sonder.internal.common.util.ClassFinder;
import com.github.tix320.sonder.internal.server.rpc.ServerRPCProtocol;

/**
 * Builder for RPC protocol {@link ServerRPCProtocol}.
 */
public class RPCProtocolBuilder {

	private String[] packagesToScan;

	private final List<AnnotationInterceptor<?, ?>> interceptors;

	RPCProtocolBuilder() {
		interceptors = new ArrayList<>();
	}

	/**
	 * Set packages to find origin interfaces {@link Origin}, and endpoint classes {@link Endpoint}
	 *
	 * @param packagesToScan packages.
	 *
	 * @return self
	 */
	public RPCProtocolBuilder scanPackages(String... packagesToScan) {
		this.packagesToScan = packagesToScan;
		return this;
	}

	/**
	 * Register interceptor for intercepting endpoint calls.
	 *
	 * @param interceptors to register.
	 *
	 * @return self
	 */
	public RPCProtocolBuilder registerInterceptor(AnnotationInterceptor<?, ?>... interceptors) {
		this.interceptors.addAll(Arrays.asList(interceptors));
		return this;
	}

	ClientRPCProtocol build() {
		List<Class<?>> classes = ClassFinder.getPackageClasses(packagesToScan);
		return new ClientRPCProtocol(classes, interceptors);
	}
}
