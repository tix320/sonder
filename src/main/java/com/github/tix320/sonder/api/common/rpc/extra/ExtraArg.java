package com.github.tix320.sonder.api.common.rpc.extra;

import java.lang.annotation.Annotation;

public final class ExtraArg {

	private final Object value;

	private final Annotation annotation;

	public ExtraArg(Object value, Annotation annotation) {
		this.value = value;
		this.annotation = annotation;
	}

	public Object getValue() {
		return value;
	}

	public Annotation getAnnotation() {
		return annotation;
	}
}
