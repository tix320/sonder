package com.gitlab.tixtix320.sonder.internal.common.rpc.extra;

import java.lang.annotation.Annotation;
import java.util.Objects;

import com.fasterxml.jackson.databind.JavaType;
import com.gitlab.tixtix320.sonder.internal.common.rpc.service.Param;

public final class ExtraParam extends Param {

	private final Annotation annotation;

	public ExtraParam(int index, JavaType type, Annotation annotation) {
		super(index, type);
		this.annotation = annotation;
	}

	public Annotation getAnnotation() {
		return annotation;
	}

	public <T extends Annotation> T getAnnotation(Class<T> type) {
		return type.cast(annotation);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		if (!super.equals(o)) return false;
		ExtraParam that = (ExtraParam) o;
		return annotation.equals(that.annotation);
	}

	@Override
	public int hashCode() {
		return Objects.hash(super.hashCode(), annotation);
	}

	@Override
	public String toString() {
		return "ExtraParam{" + "annotation=" + annotation + ", index=" + index + ", type=" + type + '}';
	}
}
