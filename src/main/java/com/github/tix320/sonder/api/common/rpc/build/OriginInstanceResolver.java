package com.github.tix320.sonder.api.common.rpc.build;

import java.util.Map;

/**
 * @author Tigran Sargsyan on 25-Aug-20
 */
public class OriginInstanceResolver {

	private final Map<Class<?>, ?> instances;

	public OriginInstanceResolver(Map<Class<?>, ?> instances) {
		this.instances = Map.copyOf(instances);
	}

	@SuppressWarnings("unchecked")
	public <T> T get(Class<T> clazz) {
		return (T) instances.get(clazz);
	}
}
