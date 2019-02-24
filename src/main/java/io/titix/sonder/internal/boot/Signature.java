package io.titix.sonder.internal.boot;

import java.lang.reflect.Method;
import java.util.List;

/**
 * @author Tigran.Sargsyan on 12-Dec-18
 */
abstract class Signature {

	public final String path;

	public final Class<?> clazz;

	public final Method method;

	public final List<Param> params;

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
