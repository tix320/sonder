package com.github.tix320.sonder.api.common.rpc.build;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.github.tix320.kiwi.api.check.Try;
import com.github.tix320.sonder.api.common.rpc.Endpoint;
import com.github.tix320.sonder.api.common.rpc.Origin;
import com.github.tix320.sonder.api.common.rpc.extra.EndpointExtraArgInjector;
import com.github.tix320.sonder.api.common.rpc.extra.OriginExtraArgExtractor;
import com.github.tix320.sonder.internal.common.rpc.exception.RPCProtocolConfigurationException;
import com.github.tix320.sonder.internal.common.rpc.protocol.ProtocolConfig;
import com.github.tix320.sonder.internal.common.rpc.protocol.RPCProtocol;
import com.github.tix320.sonder.internal.common.rpc.protocol.RPCProtocol.OriginInvocationHandler;
import com.github.tix320.sonder.internal.common.util.ClassFinder;

/**
 * Builder for RPC protocol {@link RPCProtocol}.
 */
public abstract class RPCProtocolBuilder {

	private final Map<Class<?>, Object> originInstances;

	private final Map<Class<?>, Object> endpointInstances;

	private final List<OriginExtraArgExtractor<?, ?>> originExtraArgExtractors;

	private final List<EndpointExtraArgInjector<?, ?>> endpointExtraArgInjectors;

	private final OriginInvocationHandler originInvocationHandler = new OriginInvocationHandler();

	private boolean built = false;

	public RPCProtocolBuilder() {
		originInstances = new HashMap<>();
		endpointInstances = new HashMap<>();
		originExtraArgExtractors = new ArrayList<>();
		endpointExtraArgInjectors = new ArrayList<>();
	}

	/**
	 * Scan packages to find origin interfaces {@link Origin} and endpoint classes {@link Endpoint}
	 *
	 * @param packagesToScan packages to scan.
	 *
	 * @return self
	 */
	public final RPCProtocolBuilder scanOriginPackages(String... packagesToScan) {
		Class<?>[] originClasses = ClassFinder.getPackageClasses(packagesToScan)
				.stream()
				.filter(aClass -> aClass.isAnnotationPresent(Origin.class))
				.toArray(Class[]::new);

		return registerOriginInterfaces(originClasses);
	}

	/**
	 * Set packages to find endpoint classes {@link Endpoint}
	 *
	 * @param packagesToScan packages to scan.
	 *
	 * @return self
	 */
	public final RPCProtocolBuilder scanEndpointPackages(String... packagesToScan) {
		Class<?>[] endpointClasses = ClassFinder.getPackageClasses(packagesToScan)
				.stream()
				.filter(aClass -> aClass.isAnnotationPresent(Endpoint.class))
				.toArray(Class[]::new);
		return registerEndpointClasses(endpointClasses);
	}

	/**
	 * Set packages to find endpoint classes {@link Endpoint}
	 *
	 * @param packagesToScan packages to scan.
	 * @param factory        to create founded class instances.
	 *
	 * @return self
	 */
	public final RPCProtocolBuilder scanEndpointPackages(List<String> packagesToScan,
														 Function<Class<?>, Object> factory) {
		List<Object> instances = ClassFinder.getPackageClasses(packagesToScan.toArray(String[]::new))
				.stream()
				.filter(aClass -> aClass.isAnnotationPresent(Endpoint.class))
				.map(factory)
				.collect(Collectors.toList());

		return registerEndpointInstances(instances);
	}

	/**
	 * Register origin interfaces {@link Origin}
	 *
	 * @param classes classes to register.
	 *
	 * @return self
	 */
	public final RPCProtocolBuilder registerOriginInterfaces(Class<?>... classes) {
		Map<Class<?>, Object> originInstances = new HashMap<>();
		for (Class<?> clazz : classes) {
			validateOriginClass(clazz);
			Object instance = Proxy.newProxyInstance(clazz.getClassLoader(), new Class[]{clazz},
					originInvocationHandler);
			originInstances.put(clazz, instance);
		}

		this.originInstances.putAll(originInstances);

		return this;
	}

	/**
	 * Process origin interfaces {@link Origin}
	 *
	 * @param consumer function for process.
	 *
	 * @return self
	 */
	public final RPCProtocolBuilder processOriginInstances(Consumer<Map<Class<?>, Object>> consumer) {
		consumer.accept(Map.copyOf(this.originInstances));
		return this;
	}

	/**
	 * Register endpoint classes {@link Endpoint}
	 *
	 * @param classes classes to register.
	 *
	 * @return self
	 */
	public final RPCProtocolBuilder registerEndpointClasses(Class<?>... classes) {
		for (Class<?> clazz : classes) {
			validateEndpointClass(clazz);
			Object instance = Try.supplyOrRethrow(() -> clazz.getConstructor().newInstance());
			endpointInstances.put(clazz, instance);
		}

		return this;
	}

	/**
	 * Register endpoint instances {@link Endpoint}
	 *
	 * @param instances to register.
	 *
	 * @return self
	 */
	public final RPCProtocolBuilder registerEndpointInstances(List<Object> instances) {
		for (Object instance : instances) {
			validateEndpointClass(instance.getClass());
			endpointInstances.put(instance.getClass(), instance);
		}

		return this;
	}

	/**
	 * Register extra argument extractor for origin methods.
	 *
	 * @param extractors to register.
	 *
	 * @return self
	 */
	public final RPCProtocolBuilder registerOriginExtraArgExtractor(OriginExtraArgExtractor<?, ?>... extractors) {
		this.originExtraArgExtractors.addAll(Arrays.asList(extractors));
		return this;
	}

	/**
	 * Register extra argument extractor for endpoint methods.
	 *
	 * @param injectors to register.
	 *
	 * @return self
	 */
	public final RPCProtocolBuilder registerEndpointExtraArgInjector(EndpointExtraArgInjector<?, ?>... injectors) {
		this.endpointExtraArgInjectors.addAll(Arrays.asList(injectors));
		return this;
	}

	public final RPCProtocol build() {
		if (built) {
			throw new IllegalStateException("Already built");
		}
		built = true;

		RPCProtocol protocol = buildOverride();
		originInvocationHandler.initProtocol(protocol);
		return protocol;
	}

	protected final ProtocolConfig getConfigs() {
		return new ProtocolConfig(originInstances, endpointInstances, originExtraArgExtractors,
				endpointExtraArgInjectors);
	}

	protected abstract RPCProtocol buildOverride();

	private void validateOriginClass(Class<?> clazz) {
		boolean isOrigin = clazz.isAnnotationPresent(Origin.class);
		boolean isEndpoint = clazz.isAnnotationPresent(Endpoint.class);
		if (isOrigin && isEndpoint) {
			throw new RPCProtocolConfigurationException(
					String.format("%s cannot have both @%s and @%s annotations", clazz, Origin.class, Endpoint.class));
		}
		else if (!isOrigin) {
			throw new RPCProtocolConfigurationException(
					String.format("%s does not have @%s annotation", clazz, Origin.class));
		}
	}

	private void validateEndpointClass(Class<?> clazz) {
		boolean isOrigin = clazz.isAnnotationPresent(Origin.class);
		boolean isEndpoint = clazz.isAnnotationPresent(Endpoint.class);
		if (isOrigin && isEndpoint) {
			throw new RPCProtocolConfigurationException(
					String.format("%s cannot have both @%s and @%s annotations", clazz, Origin.class, Endpoint.class));
		}
		else if (!isEndpoint) {
			throw new RPCProtocolConfigurationException(
					String.format("%s does not have @%s annotation", clazz, Endpoint.class));
		}
	}
}
