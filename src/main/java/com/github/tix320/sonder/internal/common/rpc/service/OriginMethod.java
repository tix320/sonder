package com.github.tix320.sonder.internal.common.rpc.service;

import java.lang.reflect.Method;
import java.util.List;

import com.fasterxml.jackson.databind.JavaType;
import com.github.tix320.sonder.internal.common.rpc.extra.ExtraParam;

/**
 * @author tix32 on 24-Feb-19
 */
public class OriginMethod extends ServiceMethod {

	private final ReturnType returnType;

	private final JavaType returnJavaType;

	private final RequestDataType requestDataType;

	public OriginMethod(String path, Method rawMethod, List<Param> simpleParams, List<ExtraParam> extraParams,
						ReturnType returnType, JavaType returnJavaType, RequestDataType requestDataType) {
		super(path, rawMethod, simpleParams, extraParams);
		this.returnType = returnType;
		this.returnJavaType = returnJavaType;
		this.requestDataType = requestDataType;
	}

	public ReturnType getReturnType() {
		return returnType;
	}

	public JavaType getReturnJavaType() {
		return returnJavaType;
	}

	public RequestDataType getRequestDataType() {
		return requestDataType;
	}

	public enum RequestDataType {
		ARGUMENTS,
		BINARY,
		TRANSFER
	}

	public enum ReturnType {
		VOID,
		ASYNC_RESPONSE,
		SUBSCRIPTION
	}
}
