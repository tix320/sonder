package com.gitlab.tixtix320.sonder.internal.common.service;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;

import com.gitlab.tixtix320.sonder.internal.common.extra.ExtraParam;

/**
 * @author Tigran.Sargsyan on 12-Dec-18
 */
public abstract class ServiceMethod {

	protected final String path;

	protected final Method rawMethod;

	protected final List<Param> simpleParams;

	protected final List<ExtraParam> extraParams;

	public ServiceMethod(String path, Method rawMethod, List<Param> simpleParams, List<ExtraParam> extraParams) {
		this.path = path;
		this.rawMethod = rawMethod;
		this.simpleParams = simpleParams;
		this.extraParams = extraParams;
	}

	public String getPath() {
		return path;
	}

	public Method getRawMethod() {
		return rawMethod;
	}

	public Class<?> getRawClass() {
		return rawMethod.getDeclaringClass();
	}

	public List<Param> getSimpleParams() {
		return simpleParams;
	}

	public List<ExtraParam> getExtraParams() {
		return extraParams;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		ServiceMethod that = (ServiceMethod) o;
		return path.equals(that.path) && rawMethod.equals(that.rawMethod) && simpleParams.equals(
				that.simpleParams) && extraParams.equals(that.extraParams);
	}

	@Override
	public int hashCode() {
		return Objects.hash(path, rawMethod, simpleParams, extraParams);
	}

	@Override
	public abstract String toString();
}
