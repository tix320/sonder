package com.github.tix320.sonder.api.common;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.github.tix320.kiwi.api.check.Try;
import com.github.tix320.sonder.api.common.communication.Protocol;
import com.github.tix320.sonder.api.common.rpc.Endpoint;
import com.github.tix320.sonder.api.common.rpc.Origin;
import com.github.tix320.sonder.api.common.rpc.build.OriginInstanceResolver;
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
public abstract class RPCProtocolBuilder<T extends Protocol> {

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
	public final RPCProtocolBuilder<T> scanOriginPackages(String... packagesToScan) {
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
	public final RPCProtocolBuilder<T> scanEndpointPackages(String... packagesToScan) {
		Class<?>[] endpointClasses = ClassFinder.getPackageClasses(packagesToScan)
				.stream()
				.filter(aClass -> aClass.isAnnotationPresent(Endpoint.class))
				.toArray(Class[]::new);
		return registerEndpointClasses(endpointClasses);
	}

	/**
	 * Set packages to find endpoint classes {@link Endpoint}
	 *
	 * @param factory        to create founded class instances.
	 * @param packagesToScan packages to scan.
	 *
	 * @return self
	 */
	public final RPCProtocolBuilder<T> scanEndpointPackages(Function<Class<?>, Object> factory,
															String... packagesToScan) {
		List<Object> instances = ClassFinder.getPackageClasses(packagesToScan)
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
	public final RPCProtocolBuilder<T> registerOriginInterfaces(Class<?>... classes) {
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
	 * Register endpoint classes {@link Endpoint}
	 *
	 * @param classes classes to register.
	 *
	 * @return self
	 */
	public final RPCProtocolBuilder<T> registerEndpointClasses(Class<?>... classes) {
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
	public final RPCProtocolBuilder<T> registerEndpointInstances(List<Object> instances) {
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
	public final RPCProtocolBuilder<T> registerOriginExtraArgExtractor(OriginExtraArgExtractor<?, ?>... extractors) {
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
	public final RPCProtocolBuilder<T> registerEndpointExtraArgInjector(EndpointExtraArgInjector<?, ?>... injectors) {
		this.endpointExtraArgInjectors.addAll(Arrays.asList(injectors));
		return this;
	}

	public final BuildResult<T> build() {
		if (built) {
			throw new IllegalStateException("Already built");
		}
		built = true;

		T protocol = buildOverride();
		originInvocationHandler.initProtocol((RPCProtocol) protocol);
		return new BuildResult<>(protocol, new OriginInstanceResolver(originInstances));
	}

	protected final ProtocolConfig getConfigs() {
		return new ProtocolConfig(originInstances, endpointInstances, originExtraArgExtractors,
				endpointExtraArgInjectors);
	}

	protected abstract T buildOverride();

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

	public static final class BuildResult<T> {
		private final T protocol;
		private final OriginInstanceResolver originInstanceResolver;

		public BuildResult(T protocol, OriginInstanceResolver originInstanceResolver) {
			this.protocol = protocol;
			this.originInstanceResolver = originInstanceResolver;
		}

		public T getProtocol() {
			return protocol;
		}

		public OriginInstanceResolver getOriginInstanceResolver() {
			return originInstanceResolver;
		}
	}
}
