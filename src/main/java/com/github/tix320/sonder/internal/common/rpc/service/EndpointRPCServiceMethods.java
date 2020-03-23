package com.github.tix320.sonder.internal.common.rpc.service;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import com.github.tix320.kiwi.api.check.Try;
import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.sonder.api.common.communication.Transfer;
import com.github.tix320.sonder.api.common.rpc.Endpoint;
import com.github.tix320.sonder.api.common.rpc.Subscription;
import com.github.tix320.sonder.api.common.rpc.extra.ExtraParamDefinition;
import com.github.tix320.sonder.internal.common.rpc.StartupException;
import com.github.tix320.sonder.internal.common.rpc.extra.ExtraParam;
import com.github.tix320.sonder.internal.common.rpc.service.EndpointMethod.ResultType;

public final class EndpointRPCServiceMethods extends RPCServiceMethods<EndpointMethod> {

	public EndpointRPCServiceMethods(List<Class<?>> classes, List<ExtraParamDefinition<?, ?>> extraParamDefinitions) {
		super(classes, extraParamDefinitions);
	}

	@Override
	protected final boolean isService(Class<?> clazz) {
		return clazz.isAnnotationPresent(Endpoint.class);
	}

	@Override
	protected final void checkService(Class<?> clazz) {
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
	protected final boolean isServiceMethod(Method method) {
		return method.isAnnotationPresent(Endpoint.class);
	}

	@Override
	protected final void checkMethod(Method method) {
		StartupException.checkAndThrow(method,
				m -> String.format("Failed to resolve endpoint method '%s'(%s), there are the following errors.",
						m.getName(), m.getDeclaringClass()),
				StartupException.throwWhen(m -> !Modifier.isPublic(m.getModifiers()), "Must be public"),
				StartupException.throwWhen(
						m -> m.isAnnotationPresent(Subscription.class) && m.getReturnType() != Observable.class,
						String.format("Return type must be `%s` when `@%s` annotation is present",
								Observable.class.getSimpleName(), Subscription.class.getSimpleName())));
	}

	@Override
	protected final String getPath(Method method) {
		return method.getDeclaringClass().getAnnotation(Endpoint.class).value() + ":" + method.getAnnotation(
				Endpoint.class).value();
	}

	@Override
	protected final EndpointMethod createServiceMethod(String path, Method method, List<Param> simpleParams,
													   List<ExtraParam> extraParams) {
		return new EndpointMethod(path, method, simpleParams, extraParams, resultType(method));
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
		else if (method.isAnnotationPresent(Subscription.class)) {
			return ResultType.SUBSCRIPTION;
		}
		else {
			return ResultType.OBJECT;
		}
	}
}
