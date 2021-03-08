package com.github.tix320.sonder.internal.common.rpc;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.Consumer;

import com.github.tix320.sonder.api.common.rpc.Endpoint;
import com.github.tix320.sonder.api.common.rpc.Origin;
import com.github.tix320.sonder.api.common.rpc.extra.EndpointExtraArgInjector;
import com.github.tix320.sonder.api.common.rpc.extra.OriginExtraArgExtractor;
import com.github.tix320.sonder.internal.common.rpc.exception.RPCProtocolConfigurationException;
import com.github.tix320.sonder.internal.common.rpc.protocol.RPCProtocol;
import com.github.tix320.sonder.internal.common.rpc.protocol.RPCProtocol.OriginInvocationHandler;
import com.github.tix320.sonder.internal.common.rpc.protocol.RPCProtocolConfig;

import static java.util.function.Predicate.not;

/**
 * Builder for RPC protocol {@link RPCProtocol}.
 */
public abstract class RPCProtocolBuilder<R extends RPCProtocol, T extends RPCProtocolBuilder<R, T>> {

	private final Map<Class<?>, Object> originInstances;

	private final Map<Class<?>, Object> endpointInstances;

	private final List<OriginExtraArgExtractor<?, ?>> originExtraArgExtractors;

	private final List<EndpointExtraArgInjector<?, ?>> endpointExtraArgInjectors;

	private final OriginInvocationHandler originInvocationHandler = new OriginInvocationHandler();

	private boolean built = false;

	protected RPCProtocolBuilder() {
		originInstances = new HashMap<>();
		endpointInstances = new HashMap<>();
		originExtraArgExtractors = new ArrayList<>();
		endpointExtraArgInjectors = new ArrayList<>();
	}

	/**
	 * Register origin interfaces {@link Origin}
	 *
	 * @param classes classes to register.
	 *
	 * @return self
	 */
	public final T registerOriginInterfaces(Class<?>... classes) {
		Map<Class<?>, Object> originInstances = new HashMap<>();
		for (Class<?> clazz : classes) {
			validateOriginClass(clazz);
			Object instance = Proxy.newProxyInstance(clazz.getClassLoader(), new Class[]{clazz},
					originInvocationHandler);
			originInstances.put(clazz, instance);
		}

		this.originInstances.putAll(originInstances);

		return getThis();
	}

	/**
	 * Process origin interfaces {@link Origin}
	 *
	 * @param consumer function for process.
	 *
	 * @return self
	 */
	public final T processOriginInstances(Consumer<Map<Class<?>, Object>> consumer) {
		consumer.accept(Map.copyOf(this.originInstances));
		return getThis();
	}

	/**
	 * Register endpoint classes {@link Endpoint}
	 *
	 * @param classes classes to register.
	 *
	 * @return self
	 */
	public final T registerEndpointClasses(Class<?>... classes) {
		for (Class<?> clazz : classes) {
			validateEndpointClass(clazz);
			Object instance = createInstance(clazz);
			endpointInstances.put(clazz, instance);
		}

		return getThis();
	}

	/**
	 * Register endpoint instances {@link Endpoint}
	 *
	 * @param instances to register.
	 *
	 * @return self
	 */
	public final T registerEndpointInstances(List<Object> instances) {
		for (Object instance : instances) {
			validateEndpointClass(instance.getClass());
			endpointInstances.put(instance.getClass(), instance);
		}

		return getThis();
	}

	/**
	 * Register extra argument extractor for origin methods.
	 *
	 * @param extractors to register.
	 *
	 * @return self
	 */
	public final T registerOriginExtraArgExtractor(OriginExtraArgExtractor<?, ?>... extractors) {
		this.originExtraArgExtractors.addAll(Arrays.asList(extractors));
		return getThis();
	}

	/**
	 * Register extra argument extractor for endpoint methods.
	 *
	 * @param injectors to register.
	 *
	 * @return self
	 */
	public final T registerEndpointExtraArgInjector(EndpointExtraArgInjector<?, ?>... injectors) {
		this.endpointExtraArgInjectors.addAll(Arrays.asList(injectors));
		return getThis();
	}

	public final R build() {
		if (built) {
			throw new IllegalStateException("Already built");
		}
		built = true;

		RPCProtocolConfig protocolConfig = new RPCProtocolConfig(originInstances, endpointInstances,
				originExtraArgExtractors, endpointExtraArgInjectors);

		R protocol = this.build(protocolConfig);

		originInvocationHandler.initProtocol(protocol);
		return protocol;
	}

	protected abstract R build(RPCProtocolConfig protocolConfig);

	@SuppressWarnings("unchecked")
	private T getThis() {
		return (T) this;
	}

	private Object createInstance(Class<?> clazz) throws RPCProtocolConfigurationException {
		Constructor<?> declaredConstructor;
		try {
			declaredConstructor = clazz.getDeclaredConstructor();
			declaredConstructor.setAccessible(true);
			return declaredConstructor.newInstance();
		} catch (NoSuchMethodException e) {
			throw new RPCProtocolConfigurationException(String.format("No-arg constructor not found in %s", clazz));
		} catch (IllegalAccessException | InstantiationException e) {
			throw new RPCProtocolConfigurationException(String.format("Cannot construct instance of %s", clazz), e);
		} catch (InvocationTargetException e) {
			throw new RPCProtocolConfigurationException(String.format("Cannot construct instance of %s", clazz),
					e.getTargetException());
		}

	}

	private void validateOriginClass(Class<?> clazz) {
		RPCProtocolConfigurationException.checkAndThrow(clazz,
				aClass -> String.format("Failed to resolve origin service(%s), there are the following errors.",
						aClass),
				RPCProtocolConfigurationException.throwWhen(not(Class::isInterface), "Must be interface"));

		boolean isOrigin = clazz.isAnnotationPresent(Origin.class);
		boolean isEndpoint = clazz.isAnnotationPresent(Endpoint.class);
		if (isOrigin && isEndpoint) {
			throw new RPCProtocolConfigurationException(
					String.format("%s cannot have both @%s and @%s annotations", clazz, Origin.class, Endpoint.class));
		} else if (!isOrigin) {
			throw new RPCProtocolConfigurationException(
					String.format("%s does not have @%s annotation", clazz, Origin.class));
		}
	}

	private void validateEndpointClass(Class<?> clazz) {
		RPCProtocolConfigurationException.checkAndThrow(clazz,
				aClass -> String.format("Failed to resolve endpoint service(%s), there are the following errors.",
						aClass), RPCProtocolConfigurationException.throwWhen(
						aClass -> Modifier.isAbstract(aClass.getModifiers()) || aClass.isEnum(),
						"Must be a concrete class"), RPCProtocolConfigurationException.throwWhen(
						aClass -> (aClass.isMemberClass() && !Modifier.isStatic(aClass.getModifiers())),
						"Must be static, when is a member class"));

		boolean isOrigin = clazz.isAnnotationPresent(Origin.class);
		boolean isEndpoint = clazz.isAnnotationPresent(Endpoint.class);
		if (isOrigin && isEndpoint) {
			throw new RPCProtocolConfigurationException(
					String.format("%s cannot have both @%s and @%s annotations", clazz, Origin.class, Endpoint.class));
		} else if (!isEndpoint) {
			throw new RPCProtocolConfigurationException(
					String.format("%s does not have @%s annotation", clazz, Endpoint.class));
		}
	}
}
