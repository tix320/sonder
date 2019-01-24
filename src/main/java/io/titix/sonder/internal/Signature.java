package io.titix.sonder.internal;

import java.lang.reflect.Method;
import java.util.List;

/**
 * @author Tigran.Sargsyan on 12-Dec-18
 */
final class Signature {

	final String path;

	final Class<?> clazz;

	final Method method;

	final List<Param> params;

	Signature(String path, Class<?> clazz, Method method, List<Param> params) {
		this.path = path;
		this.clazz = clazz;
		this.method = method;
		this.params = params;
	}

	@Override
	public String toString() {
		return "Signature{" +
				"path='" + path + '\'' +
				", clazz=" + clazz +
				", method=" + method +
				", params=" + params +
				'}';
	}
}
