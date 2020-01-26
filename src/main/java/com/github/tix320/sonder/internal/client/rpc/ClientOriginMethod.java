package com.github.tix320.sonder.internal.client.rpc;

import java.lang.reflect.Method;
import java.util.List;

import com.fasterxml.jackson.databind.JavaType;
import com.github.tix320.sonder.internal.common.rpc.extra.ExtraParam;
import com.github.tix320.sonder.internal.common.rpc.service.OriginMethod;
import com.github.tix320.sonder.internal.common.rpc.service.Param;

public final class ClientOriginMethod extends OriginMethod {

	private final Destination destination;

	public ClientOriginMethod(String path, Method rawMethod, List<Param> simpleParams, List<ExtraParam> extraParams,
							  ReturnType returnType, JavaType returnJavaType, RequestDataType requestDataType,
							  Destination destination) {
		super(path, rawMethod, simpleParams, extraParams, returnType, returnJavaType, requestDataType);
		this.destination = destination;
	}

	public Destination getDestination() {
		return destination;
	}

	public enum Destination {
		CLIENT,
		SERVER
	}
}
