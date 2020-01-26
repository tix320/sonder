package com.github.tix320.sonder.internal.client.rpc;

import java.lang.reflect.Method;
import java.util.List;

import com.github.tix320.sonder.internal.common.rpc.extra.ExtraParam;
import com.github.tix320.sonder.internal.common.rpc.service.EndpointMethod;
import com.github.tix320.sonder.internal.common.rpc.service.Param;

public final class ClientEndpointMethod extends EndpointMethod {

	public ClientEndpointMethod(String path, Method method, List<Param> simpleParams, List<ExtraParam> extraParams,
								ResultType resultType) {
		super(path, method, simpleParams, extraParams, resultType);
	}
}
