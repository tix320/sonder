package com.github.tix320.sonder.internal.server.rpc;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import com.github.tix320.sonder.api.common.rpc.extra.ClientID;
import com.github.tix320.sonder.internal.common.rpc.extra.ExtraParam;
import com.github.tix320.sonder.internal.common.rpc.service.EndpointMethod;
import com.github.tix320.sonder.internal.common.rpc.service.EndpointRPCServiceMethods;
import com.github.tix320.sonder.internal.common.rpc.service.Param;
import com.github.tix320.sonder.internal.common.rpc.service.RPCServiceMethods;

public class ServerEndpointRPCServiceMethods extends EndpointRPCServiceMethods<EndpointMethod> {

	public ServerEndpointRPCServiceMethods(List<Class<?>> classes) {
		super(classes);
	}

	@Override
	protected EndpointMethod createServiceMethod(String path, Method method, List<Param> simpleParams,
												 List<ExtraParam> extraParams) {
		return new EndpointMethod(path, method, simpleParams, extraParams, resultType(method));
	}

	@Override
	protected void peek(EndpointMethod method) {

	}

	@Override
	protected Map<Class<? extends Annotation>, RPCServiceMethods.ExtraParamDefinition> getExtraParamDefinitions() {
		return Map.of(ClientID.class, new RPCServiceMethods.ExtraParamDefinition(ClientID.class, long.class, false));
	}
}
