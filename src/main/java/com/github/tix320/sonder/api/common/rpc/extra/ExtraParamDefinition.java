package com.github.tix320.sonder.api.common.rpc.extra;

import java.lang.annotation.Annotation;

public class ExtraParamDefinition<A extends Annotation, T> {

	private final Class<A> annotationType;

	private final Class<T> paramType;

	private final boolean isRequired;

	public ExtraParamDefinition(Class<A> annotationType, Class<T> paramType, boolean isRequired) {
		this.annotationType = annotationType;
		this.paramType = paramType;
		this.isRequired = isRequired;
	}

	public Class<A> getAnnotationType() {
		return annotationType;
	}

	public Class<T> getParamType() {
		return paramType;
	}

	public boolean isRequired() {
		return isRequired;
	}
}
