package com.github.tix320.sonder.internal.common.rpc.protocol;

import java.util.List;
import java.util.Map;

import com.github.tix320.sonder.api.common.rpc.extra.EndpointExtraArgInjector;
import com.github.tix320.sonder.api.common.rpc.extra.OriginExtraArgExtractor;

/**
 * @author Tigran Sargsyan on 25-Aug-20
 */
public final class ProtocolConfig {

	private final Map<Class<?>, Object> originInstances;

	private final Map<Class<?>, Object> endpointInstances;

	private final List<OriginExtraArgExtractor<?, ?>> originExtraArgExtractors;

	private final List<EndpointExtraArgInjector<?, ?>> endpointExtraArgInjectors;

	public ProtocolConfig(Map<Class<?>, Object> originInstances, Map<Class<?>, Object> endpointInstances,
						  List<OriginExtraArgExtractor<?, ?>> originExtraArgExtractors,
						  List<EndpointExtraArgInjector<?, ?>> endpointExtraArgInjectors) {
		this.originInstances = Map.copyOf(originInstances);
		this.endpointInstances = Map.copyOf(endpointInstances);
		this.originExtraArgExtractors = List.copyOf(originExtraArgExtractors);
		this.endpointExtraArgInjectors = List.copyOf(endpointExtraArgInjectors);
	}

	public Map<Class<?>, Object> getOriginInstances() {
		return originInstances;
	}

	public Map<Class<?>, Object> getEndpointInstances() {
		return endpointInstances;
	}

	public List<OriginExtraArgExtractor<?, ?>> getOriginExtraArgExtractors() {
		return originExtraArgExtractors;
	}

	public List<EndpointExtraArgInjector<?, ?>> getEndpointExtraArgInjectors() {
		return endpointExtraArgInjectors;
	}
}
