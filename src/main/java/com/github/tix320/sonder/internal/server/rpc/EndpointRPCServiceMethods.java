package com.github.tix320.sonder.internal.server.rpc;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;

import com.github.tix320.sonder.api.common.communication.Transfer;
import com.github.tix320.sonder.api.common.rpc.Endpoint;
import com.github.tix320.sonder.api.common.rpc.extra.ClientID;
import com.github.tix320.sonder.internal.common.rpc.StartupException;
import com.github.tix320.sonder.internal.common.rpc.extra.ExtraParam;
import com.github.tix320.sonder.internal.common.rpc.service.EndpointMethod;
import com.github.tix320.sonder.internal.common.rpc.service.EndpointMethod.ResultType;
import com.github.tix320.sonder.internal.common.rpc.service.Param;
import com.github.tix320.sonder.internal.common.rpc.service.RPCServiceMethods;
import com.github.tix320.kiwi.api.check.Try;

public class EndpointRPCServiceMethods extends RPCServiceMethods<EndpointMethod> {

	public EndpointRPCServiceMethods(List<Class<?>> classes) {
		super(classes);
	}

	@Override
	protected boolean isService(Class<?> clazz) {
		return clazz.isAnnotationPresent(Endpoint.class);
	}

	@Override
	protected void checkService(Class<?> clazz) {
		StartupException.checkAndThrow(clazz,
				aClass -> String.format("Failed to resolve endpoint service(%s), there are the following errors.",
						aClass),
				StartupException.throwWhen(aClass -> Modifier.isAbstract(aClass.getModifiers()) || aClass.isEnum(),
						"Must be a concrete class"), StartupException.throwWhen(
						aClass -> (aClass.isMemberClass() && !Modifier.isStatic(aClass.getModifiers())),
						"Must be static, when is a member class"),
				StartupException.throwWhen(aClass -> !Modifier.isPublic(aClass.getModifiers()), "Must be public"),
				StartupException.throwWhen(aClass -> Try.supply(aClass::getConstructor)
						.filter(constructor -> Modifier.isPublic(constructor.getModifiers()))
						.isUseless(), "Must have public no-args constructor"));
	}

	@Override
	protected boolean isServiceMethod(Method method) {
		return method.isAnnotationPresent(Endpoint.class);
	}

	@Override
	protected void checkMethod(Method method) {
		StartupException.checkAndThrow(method,
				m -> String.format("Failed to resolve endpoint method '%s'(%s), there are the following errors.",
						m.getName(), m.getDeclaringClass()),
				StartupException.throwWhen(m -> !Modifier.isPublic(m.getModifiers()), "Must be public"));
	}

	@Override
	protected String getPath(Method method) {
		return method.getDeclaringClass().getAnnotation(Endpoint.class).value() + ":" + method.getAnnotation(
				Endpoint.class).value();
	}

	@Override
	protected EndpointMethod createServiceMethod(String path, Method method, List<Param> simpleParams,
												 List<ExtraParam> extraParams) {
		return new EndpointMethod(path, method, simpleParams, extraParams, resultType(method));
	}

	@Override
	protected Map<Class<? extends Annotation>, RPCServiceMethods.ExtraParamDefinition> getExtraParamDefinitions() {
		return Map.of(ClientID.class, new RPCServiceMethods.ExtraParamDefinition(ClientID.class, long.class, false));
	}

	private ResultType resultType(Method method) {
		Class<?> returnType = method.getReturnType();
		if (returnType == void.class) {
			return ResultType.VOID;
		}
		else if (returnType == byte[].class) {
			return ResultType.BINARY;
		}
		else if (returnType == Transfer.class) {
			return ResultType.TRANSFER;
		}
		else {
			return ResultType.OBJECT;
		}
	}
}
