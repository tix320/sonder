package com.github.tix320.sonder.api.common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.github.tix320.sonder.api.common.rpc.Endpoint;
import com.github.tix320.sonder.api.common.rpc.Origin;
import com.github.tix320.sonder.api.common.rpc.extra.EndpointExtraArgExtractor;
import com.github.tix320.sonder.api.common.rpc.extra.OriginExtraArgExtractor;
import com.github.tix320.sonder.internal.common.ProtocolOrientation;
import com.github.tix320.sonder.internal.common.rpc.protocol.RPCProtocol;
import com.github.tix320.sonder.internal.common.util.ClassFinder;
import com.github.tix320.sonder.internal.event.SonderEventDispatcher;

/**
 * Builder for RPC protocol {@link RPCProtocol}.
 */
public final class RPCProtocolBuilder {

	private final ProtocolOrientation orientation;

	private final List<String> packagesToScan;

	private final List<Class<?>> classes;

	private final List<OriginExtraArgExtractor<?>> originExtraArgExtractors;

	private final List<EndpointExtraArgExtractor<?, ?>> endpointExtraArgExtractors;

	private final SonderEventDispatcher<?> sonderEventDispatcher;

	public RPCProtocolBuilder(ProtocolOrientation orientation, SonderEventDispatcher<?> sonderEventDispatcher) {
		this.orientation = orientation;
		this.sonderEventDispatcher = sonderEventDispatcher;
		packagesToScan = new ArrayList<>();
		classes = new ArrayList<>();
		originExtraArgExtractors = new ArrayList<>();
		endpointExtraArgExtractors = new ArrayList<>();
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
	 * Register extra argument extractor for origin methods.
	 *
	 * @param extractors to register.
	 *
	 * @return self
	 */
	public RPCProtocolBuilder registerOriginExtraArgExtractor(OriginExtraArgExtractor<?>... extractors) {
		this.originExtraArgExtractors.addAll(Arrays.asList(extractors));
		return this;
	}

	/**
	 * Register extra argument extractor for endpoint methods.
	 *
	 * @param extractors to register.
	 *
	 * @return self
	 */
	public RPCProtocolBuilder registerEndpointExtraArgExtractor(EndpointExtraArgExtractor<?, ?>... extractors) {
		this.endpointExtraArgExtractors.addAll(Arrays.asList(extractors));
		return this;
	}

	private List<Class<?>> resolveClasses() {
		List<Class<?>> packageClasses = ClassFinder.getPackageClasses(packagesToScan);
		List<Class<?>> classes = this.classes;
		List<Class<?>> allClasses = new ArrayList<>();
		allClasses.addAll(packageClasses);
		allClasses.addAll(classes);
		return allClasses;
	}

	public RPCProtocol build() {
		List<Class<?>> classes = resolveClasses();
		return new RPCProtocol(orientation, sonderEventDispatcher, classes, originExtraArgExtractors,
				endpointExtraArgExtractors);
	}
}
