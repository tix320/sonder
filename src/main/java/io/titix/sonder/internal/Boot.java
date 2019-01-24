package io.titix.sonder.internal;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import io.titix.sonder.Endpoint;
import io.titix.sonder.Origin;

/**
 * @author Tigran.Sargsyan on 12-Dec-18
 */
public final class Boot {

	private final OriginHolder originHolder;

	private final EndpointHolder endpointHolder;

	public Boot(String[] packages) {
		List<Class<?>> classes = Config.getPackageClasses(packages).stream()
				.peek(Boot::checkClass).collect(Collectors.toList());

		OriginHolder originHolder = new OriginHolder(classes.stream()
				.filter(clazz -> clazz.isAnnotationPresent(Origin.class))
				.collect(Collectors.toList()));

		EndpointHolder endpointHolder = new EndpointHolder(classes.stream()
				.filter(clazz -> clazz.isAnnotationPresent(Endpoint.class))
				.collect(Collectors.toList()));

		this.originHolder = originHolder;
		this.endpointHolder = endpointHolder;
	}

	Collection<Class<?>> getOriginServices() {
		return originHolder.getServices();
	}

	Collection<Class<?>> getEndpointServices() {
		return endpointHolder.getServices();
	}

	Signature getOrigin(Method method) {
		return originHolder.getSignature(method);
	}

	Signature getEndpoint(String path) {
		return endpointHolder.getSignature(path);
	}

	private static void checkClass(Class<?> clazz) {
		if (clazz.isAnnotationPresent(Origin.class)) {
			if (clazz.isAnnotationPresent(Endpoint.class)) {
				throw new BootException("Must be one of the annotations: " + Origin.class.getName() + " or " + Endpoint.class
						.getName() + ". in class " + clazz.getName());
			}
		}
	}
}
