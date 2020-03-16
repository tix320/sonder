package com.github.tix320.sonder.internal.common.rpc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.github.tix320.kiwi.api.proxy.AnnotationInterceptor;
import com.github.tix320.sonder.api.common.rpc.Endpoint;
import com.github.tix320.sonder.api.common.rpc.Origin;
import com.github.tix320.sonder.internal.common.util.ClassFinder;

public abstract class BaseRPCProtocolBuilder<E extends BaseRPCProtocolBuilder<E, T>, T> {

	private final List<String> packagesToScan;

	private final List<Class<?>> classes;

	protected final List<AnnotationInterceptor<?, ?>> interceptors;

	public BaseRPCProtocolBuilder() {
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
	public E scanPackages(String... packagesToScan) {
		this.packagesToScan.addAll(Arrays.asList(packagesToScan));
		return getThis();
	}


	/**
	 * Set classes to find origin interfaces {@link Origin}, and endpoint classes {@link Endpoint}
	 *
	 * @param classes classes to scan.
	 *
	 * @return self
	 */
	public E scanClasses(Class<?>... classes) {
		this.classes.addAll(Arrays.asList(classes));
		return getThis();
	}

	/**
	 * Register interceptor for intercepting endpoint calls.
	 *
	 * @param interceptors to register.
	 *
	 * @return self
	 */
	public E registerInterceptor(AnnotationInterceptor<?, ?>... interceptors) {
		this.interceptors.addAll(Arrays.asList(interceptors));
		return getThis();
	}

	protected List<Class<?>> resolveClasses() {
		List<Class<?>> packageClasses = ClassFinder.getPackageClasses(packagesToScan);
		List<Class<?>> classes = this.classes;
		List<Class<?>> allClasses = new ArrayList<>();
		allClasses.addAll(packageClasses);
		allClasses.addAll(classes);
		return allClasses;
	}

	protected abstract T build();

	protected abstract E getThis();
}
