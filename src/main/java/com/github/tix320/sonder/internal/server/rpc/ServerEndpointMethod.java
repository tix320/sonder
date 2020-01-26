package com.github.tix320.sonder.internal.server.rpc;

import java.lang.reflect.Method;
import java.util.List;

import com.github.tix320.sonder.internal.common.rpc.extra.ExtraParam;
import com.github.tix320.sonder.internal.common.rpc.service.EndpointMethod;
import com.github.tix320.sonder.internal.common.rpc.service.Param;

public class ServerEndpointMethod extends EndpointMethod {
	public ServerEndpointMethod(String path, Method method, List<Param> simpleParams, List<ExtraParam> extraParams,
								ResultType resultType) {
		super(path, method, simpleParams, extraParams, resultType);
	}
}
