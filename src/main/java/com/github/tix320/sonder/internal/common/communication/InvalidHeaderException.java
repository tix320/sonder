package com.github.tix320.sonder.internal.common.communication;

public final class InvalidHeaderException extends RuntimeException {

	public InvalidHeaderException(String header, Object value, Class<?> expectedType) {
		super(String.format("Header {%s = %s} must be of type %s", header, value, expectedType.getName()));
	}
}
