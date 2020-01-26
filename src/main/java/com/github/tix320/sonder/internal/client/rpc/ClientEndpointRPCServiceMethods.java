package com.github.tix320.sonder.internal.client.rpc;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import com.github.tix320.sonder.api.common.rpc.extra.ClientID;
import com.github.tix320.sonder.internal.common.rpc.extra.ExtraParam;
import com.github.tix320.sonder.internal.common.rpc.service.EndpointRPCServiceMethods;
import com.github.tix320.sonder.internal.common.rpc.service.Param;
import com.github.tix320.sonder.internal.common.rpc.service.RPCServiceMethods;

public class ClientEndpointRPCServiceMethods extends EndpointRPCServiceMethods<ClientEndpointMethod> {

	public ClientEndpointRPCServiceMethods(List<Class<?>> classes) {
		super(classes);
	}

	@Override
	protected final ClientEndpointMethod createServiceMethod(String path, Method method, List<Param> simpleParams,
															 List<ExtraParam> extraParams) {
		return new ClientEndpointMethod(path, method, simpleParams, extraParams, resultType(method));
	}

	@Override
	protected void peek(ClientEndpointMethod method) {

	}

	@Override
	protected Map<Class<? extends Annotation>, ExtraParamDefinition> getExtraParamDefinitions() {
		return Map.of(ClientID.class, new RPCServiceMethods.ExtraParamDefinition(ClientID.class, Long.class, false));
	}
}
