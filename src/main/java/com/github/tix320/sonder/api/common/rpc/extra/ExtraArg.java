package com.github.tix320.sonder.api.common.rpc.extra;

import java.lang.annotation.Annotation;

public final class ExtraArg<A extends Annotation> {

	private final Object value;

	private final A annotation;

	public ExtraArg(Object value, A annotation) {
		this.value = value;
		this.annotation = annotation;
	}

	public Object getValue() {
		return value;
	}

	public A getAnnotation() {
		return annotation;
	}
}
