package com.github.tix320.sonder.internal.common.ro;

import java.lang.invoke.MethodHandle;

import com.fasterxml.jackson.databind.JavaType;

public class MethodMetaData {

	private final MethodReturnType methodReturnType;

	private final JavaType returnType;

	private final MethodHandle methodHandle;

	private final String methodId;

	public MethodMetaData(MethodReturnType methodReturnType, JavaType returnType, MethodHandle methodHandle,
						  String methodId) {
		this.methodReturnType = methodReturnType;
		this.returnType = returnType;
		this.methodHandle = methodHandle;
		this.methodId = methodId;
	}

	public MethodReturnType getMethodReturnType() {
		return methodReturnType;
	}

	public JavaType getReturnType() {
		return returnType;
	}

	public MethodHandle getMethodHandle() {
		return methodHandle;
	}

	public String getMethodId() {
		return methodId;
	}
}
