package com.github.tix320.sonder.internal.common.rpc.service;

import java.lang.reflect.Method;
import java.util.List;

import com.github.tix320.sonder.internal.common.rpc.extra.ExtraParam;

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
}
