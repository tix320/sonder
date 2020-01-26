package com.github.tix320.sonder.internal.server.rpc;

import java.lang.reflect.Method;
import java.util.List;

import com.fasterxml.jackson.databind.JavaType;
import com.github.tix320.sonder.internal.common.rpc.extra.ExtraParam;
import com.github.tix320.sonder.internal.common.rpc.service.OriginMethod;
import com.github.tix320.sonder.internal.common.rpc.service.Param;

public class ServerOriginMethod extends OriginMethod {

	public ServerOriginMethod(String path, Method rawMethod, List<Param> simpleParams, List<ExtraParam> extraParams,
							  ReturnType returnType, JavaType returnJavaType, RequestDataType requestDataType) {
		super(path, rawMethod, simpleParams, extraParams, returnType, returnJavaType, requestDataType);
	}
}
