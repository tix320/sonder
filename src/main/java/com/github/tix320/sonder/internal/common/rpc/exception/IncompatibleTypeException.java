package com.github.tix320.sonder.internal.common.rpc.exception;

import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class IncompatibleTypeException extends RPCProtocolException {

	private static final Pattern CLASS_CAST_MESSAGE_PATTERN = Pattern.compile(
			".*class (([a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)*[0-9a-zA-Z_])*) cannot be cast to class (([a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)*[0-9a-zA-Z_])*)\\s*.*");

	public IncompatibleTypeException(String message, Throwable cause) {
		super(message, cause);
	}

	public static IncompatibleTypeException forMethodReturnType(Method method, ClassCastException classCastException) {
		Matcher matcher = CLASS_CAST_MESSAGE_PATTERN.matcher(classCastException.getMessage());

		String message;
		if (matcher.matches()) {
			String actualClassName = matcher.group(1);
			String expectedClassName = matcher.group(4);
			message = String.format("You are expected %s from method %s(%s), but received real type is %s",
					expectedClassName, method.getName(), method.getDeclaringClass().getName(), actualClassName);
		}
		else {
			message = "Unknown types";
		}
		return new IncompatibleTypeException(message, classCastException);
	}
}
