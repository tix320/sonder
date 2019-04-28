package io.titix.sonder.internal;

import java.lang.annotation.Annotation;

public class ExtraParam extends Param {

	public final Annotation annotation;

	ExtraParam(int index, Class<?> type, Annotation annotation) {
		super(index, type);
		this.annotation = annotation;
	}
}
