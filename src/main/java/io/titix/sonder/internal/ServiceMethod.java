package io.titix.sonder.internal;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;

/**
 * @author Tigran.Sargsyan on 12-Dec-18
 */
public abstract class ServiceMethod {

	public final String path;

	public final Class<?> clazz;

	public final Method method;

	public final List<Param> simpleParams;

	public final List<ExtraParam> extraParams;

	public ServiceMethod(String path, Class<?> clazz, Method method, List<Param> simpleParams,
						 List<ExtraParam> extraParams) {
		this.path = path;
		this.clazz = clazz;
		this.method = method;
		this.simpleParams = simpleParams;
		this.extraParams = extraParams;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		ServiceMethod serviceMethod = (ServiceMethod) o;
		return path.equals(serviceMethod.path) && clazz.equals(serviceMethod.clazz) && method.equals(
				serviceMethod.method) && simpleParams.equals(serviceMethod.simpleParams) && extraParams.equals(
				serviceMethod.extraParams);
	}

	@Override
	public int hashCode() {
		return Objects.hash(path, clazz, method, simpleParams, extraParams);
	}

	@Override
	public String toString() {
		return "Signature{" + "path='" + path + '\'' + ", clazz=" + clazz + ", method=" + method + ", simpleParams=" + simpleParams + ", extraParams=" + extraParams + '}';
	}
}
