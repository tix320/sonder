package com.github.tix320.sonder.internal.common.rpc.service;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import com.github.tix320.kiwi.api.check.Try;
import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.sonder.api.common.communication.Transfer;
import com.github.tix320.sonder.api.common.rpc.Endpoint;
import com.github.tix320.sonder.api.common.rpc.Subscribe;
import com.github.tix320.sonder.internal.common.rpc.StartupException;
import com.github.tix320.sonder.internal.common.rpc.service.EndpointMethod.ResultType;

public abstract class EndpointRPCServiceMethods<T extends EndpointMethod> extends RPCServiceMethods<T> {

	public EndpointRPCServiceMethods(List<Class<?>> classes) {
		super(classes);
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
						m -> m.isAnnotationPresent(Subscribe.class) && m.getReturnType() != Observable.class,
						String.format("Return type must be `%s` when `@%s` annotation is present",
								Observable.class.getSimpleName(), Subscribe.class.getSimpleName())));
	}

	@Override
	protected final String getPath(Method method) {
		return method.getDeclaringClass().getAnnotation(Endpoint.class).value() + ":" + method.getAnnotation(
				Endpoint.class).value();
	}

	protected final ResultType resultType(Method method) {
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
		else if (method.isAnnotationPresent(Subscribe.class)) {
			return ResultType.SUBSCRIPTION;
		}
		else {
			return ResultType.OBJECT;
		}
	}
}
