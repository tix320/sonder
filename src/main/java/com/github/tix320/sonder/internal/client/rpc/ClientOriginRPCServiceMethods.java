package com.github.tix320.sonder.internal.client.rpc;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import com.github.tix320.sonder.api.common.rpc.extra.ClientID;
import com.github.tix320.sonder.internal.common.rpc.extra.ExtraParam;
import com.github.tix320.sonder.internal.common.rpc.service.OriginMethod;
import com.github.tix320.sonder.internal.common.rpc.service.OriginRPCServiceMethods;
import com.github.tix320.sonder.internal.common.rpc.service.Param;

public final class ClientOriginRPCServiceMethods extends OriginRPCServiceMethods<OriginMethod> {

	public ClientOriginRPCServiceMethods(List<Class<?>> classes) {
		super(classes);
	}

	@Override
	protected final Map<Class<? extends Annotation>, ExtraParamDefinition> getExtraParamDefinitions() {
		return Map.of(ClientID.class, new ExtraParamDefinition(ClientID.class, long.class, false));
	}

	@Override
	protected final OriginMethod createServiceMethod(String path, Method method, List<Param> simpleParams,
													 List<ExtraParam> extraParams) {
		return new OriginMethod(path, method, simpleParams, extraParams, constructReturnType(method),
				constructReturnJavaType(method), requestDataType(simpleParams));
	}

	@Override
	protected void peek(OriginMethod method) {

	}
}
