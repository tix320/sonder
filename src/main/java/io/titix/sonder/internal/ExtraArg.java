package io.titix.sonder.internal;

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

	@SuppressWarnings("unchecked")
	public <T extends Annotation> T getAnnotation(Class<T> annotationType) {
		return (T) annotation;
	}
}
