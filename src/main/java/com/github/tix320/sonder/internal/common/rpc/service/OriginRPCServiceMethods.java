package com.github.tix320.sonder.internal.common.rpc.service;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.github.tix320.kiwi.api.reactive.observable.MonoObservable;
import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.sonder.api.common.communication.Transfer;
import com.github.tix320.sonder.api.common.rpc.Origin;
import com.github.tix320.sonder.api.common.rpc.Subscription;
import com.github.tix320.sonder.internal.common.rpc.StartupException;
import com.github.tix320.sonder.internal.common.rpc.service.OriginMethod.RequestDataType;
import com.github.tix320.sonder.internal.common.rpc.service.OriginMethod.ReturnType;

import static java.util.function.Predicate.not;

public abstract class OriginRPCServiceMethods<T extends OriginMethod> extends RPCServiceMethods<T> {

	public OriginRPCServiceMethods(List<Class<?>> classes) {
		super(classes);
	}

	@Override
	protected final boolean isService(Class<?> clazz) {
		return clazz.isAnnotationPresent(Origin.class);
	}

	@Override
	protected final void checkService(Class<?> clazz) {
		StartupException.checkAndThrow(clazz,
				aClass -> String.format("Failed to resolve origin service(%s), there are the following errors.",
						aClass), StartupException.throwWhen(not(Class::isInterface), "Must be interface"),
				StartupException.throwWhen(not(aClass -> Modifier.isPublic(aClass.getModifiers())), "Must be public"));
	}

	@Override
	protected final boolean isServiceMethod(Method method) {
		return method.isAnnotationPresent(Origin.class);
	}

	@Override
	protected final void checkMethod(Method method) {
		StartupException.checkAndThrow(method,
				m -> String.format("Failed to resolve origin method '%s'(%s), there are the following errors. ",
						m.getName(), m.getDeclaringClass()), StartupException.throwWhen(
						m -> m.isAnnotationPresent(Subscription.class)
							 && m.getReturnType() != Observable.class
							 && (m.getReturnType() != void.class && m.getReturnType() != MonoObservable.class),
						String.format(
								"Return type must be `%s` when `@%s` annotation is present, otherwise must be be `void` or `%s`",
								Observable.class.getSimpleName(), Subscription.class.getSimpleName(),
								MonoObservable.class.getSimpleName())),
				StartupException.throwWhen(not(m -> Modifier.isPublic(m.getModifiers())), "Must be public"));
	}

	@Override
	protected final String getPath(Method method) {
		return method.getDeclaringClass().getAnnotation(Origin.class).value() + ":" + method.getAnnotation(Origin.class)
				.value();
	}

	protected final ReturnType constructReturnType(Method method) {
		Class<?> returnType = method.getReturnType();
		if (returnType == void.class) {
			return ReturnType.VOID;
		}
		else if (returnType == MonoObservable.class) {
			return ReturnType.ASYNC_RESPONSE;
		}
		else if (method.isAnnotationPresent(Subscription.class)) {
			return ReturnType.SUBSCRIPTION;
		}
		else {
			throw new IllegalStateException(returnType.getName());
		}
	}

	protected final JavaType constructReturnJavaType(Method method) {
		TypeFactory typeFactory = new ObjectMapper().getTypeFactory();
		Type returnType = method.getGenericReturnType();
		if (returnType instanceof Class) { // Observable
			return typeFactory.constructType(Object.class);
		}
		else if (returnType instanceof ParameterizedType) { // Observable<...>
			Type argument = ((ParameterizedType) returnType).getActualTypeArguments()[0]; // <...>
			return typeFactory.constructType(argument);
		}
		else {
			throw new IllegalStateException(returnType.getTypeName());
		}
	}

	protected final RequestDataType requestDataType(List<Param> simpleParams) {
		if (simpleParams.size() == 1) {
			Param param = simpleParams.get(0);
			if (param.getType().getRawClass() == byte[].class) {
				return RequestDataType.BINARY;
			}
			else if (param.getType().getRawClass() == Transfer.class) {
				return RequestDataType.TRANSFER;
			}
		}
		return RequestDataType.ARGUMENTS;
	}
}
