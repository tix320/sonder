package com.github.tix320.sonder.internal.server.rpc;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import com.github.tix320.sonder.api.common.rpc.extra.ClientID;
import com.github.tix320.sonder.internal.common.rpc.extra.ExtraParam;
import com.github.tix320.sonder.internal.common.rpc.service.OriginRPCServiceMethods;
import com.github.tix320.sonder.internal.common.rpc.service.Param;

public class ServerOriginRPCServiceMethods extends OriginRPCServiceMethods<ServerOriginMethod> {

	public ServerOriginRPCServiceMethods(List<Class<?>> classes) {
		super(classes);
	}

	@Override
	protected ServerOriginMethod createServiceMethod(String path, Method method, List<Param> simpleParams,
													 List<ExtraParam> extraParams) {
		return new ServerOriginMethod(path, method, simpleParams, extraParams, constructReturnType(method),
				constructReturnJavaType(method), requestDataType(simpleParams));
	}

	@Override
	protected void peek(ServerOriginMethod method) {

	}

	@Override
	protected Map<Class<? extends Annotation>, ExtraParamDefinition> getExtraParamDefinitions() {
		return Map.of(ClientID.class, new ExtraParamDefinition(ClientID.class, long.class, true));
	}
}
