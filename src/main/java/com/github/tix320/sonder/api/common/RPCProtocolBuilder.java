package com.github.tix320.sonder.api.common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.github.tix320.kiwi.api.proxy.AnnotationInterceptor;
import com.github.tix320.sonder.api.common.rpc.Endpoint;
import com.github.tix320.sonder.api.common.rpc.Origin;
import com.github.tix320.sonder.internal.common.ProtocolOrientation;
import com.github.tix320.sonder.internal.common.rpc.RPCProtocol;
import com.github.tix320.sonder.internal.common.util.ClassFinder;

/**
 * Builder for RPC protocol {@link RPCProtocol}.
 */
public class RPCProtocolBuilder {

	private final ProtocolOrientation orientation;

	private final List<String> packagesToScan;

	private final List<Class<?>> classes;

	protected final List<AnnotationInterceptor<?, ?>> interceptors;

	public RPCProtocolBuilder(ProtocolOrientation orientation) {
		this.orientation = orientation;
		packagesToScan = new ArrayList<>();
		classes = new ArrayList<>();
		interceptors = new ArrayList<>();
	}

	/**
	 * Set packages to find origin interfaces {@link Origin}, and endpoint classes {@link Endpoint}
	 *
	 * @param packagesToScan packages to scan.
	 *
	 * @return self
	 */
	public RPCProtocolBuilder scanPackages(String... packagesToScan) {
		this.packagesToScan.addAll(Arrays.asList(packagesToScan));
		return this;
	}


	/**
	 * Set classes to find origin interfaces {@link Origin}, and endpoint classes {@link Endpoint}
	 *
	 * @param classes classes to scan.
	 *
	 * @return self
	 */
	public RPCProtocolBuilder scanClasses(Class<?>... classes) {
		this.classes.addAll(Arrays.asList(classes));
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

	protected List<Class<?>> resolveClasses() {
		List<Class<?>> packageClasses = ClassFinder.getPackageClasses(packagesToScan);
		List<Class<?>> classes = this.classes;
		List<Class<?>> allClasses = new ArrayList<>();
		allClasses.addAll(packageClasses);
		allClasses.addAll(classes);
		return allClasses;
	}

	public RPCProtocol build() {
		List<Class<?>> classes = resolveClasses();
		return new RPCProtocol(orientation, classes, interceptors);
	}
}
