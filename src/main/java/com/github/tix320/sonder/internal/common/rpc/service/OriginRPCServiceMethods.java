package com.github.tix320.sonder.internal.common.rpc.service;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.github.tix320.kiwi.api.reactive.observable.MonoObservable;
import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.sonder.api.common.communication.Transfer;
import com.github.tix320.sonder.api.common.rpc.Origin;
import com.github.tix320.sonder.api.common.rpc.Response;
import com.github.tix320.sonder.api.common.rpc.Subscription;
import com.github.tix320.sonder.api.common.rpc.extra.ExtraParamDefinition;
import com.github.tix320.sonder.internal.common.rpc.exception.RPCProtocolConfigurationException;
import com.github.tix320.sonder.internal.common.rpc.extra.ExtraParam;
import com.github.tix320.sonder.internal.common.rpc.service.OriginMethod.RequestDataType;
import com.github.tix320.sonder.internal.common.rpc.service.OriginMethod.ReturnType;

import static java.util.function.Predicate.not;

public final class OriginRPCServiceMethods extends RPCServiceMethods<OriginMethod> {

	public OriginRPCServiceMethods(Set<Class<?>> classes, List<ExtraParamDefinition<?, ?>> extraParamDefinitions) {
		super(classes, extraParamDefinitions);
	}

	@Override
	protected final void checkService(Class<?> clazz) {
		RPCProtocolConfigurationException.checkAndThrow(clazz,
				aClass -> String.format("Failed to resolve origin service(%s), there are the following errors.",
						aClass),
				RPCProtocolConfigurationException.throwWhen(not(Class::isInterface), "Must be interface"),
				RPCProtocolConfigurationException.throwWhen(not(aClass -> Modifier.isPublic(aClass.getModifiers())),
						"Must be public"));
	}

	@Override
	protected final boolean isServiceMethod(Method method) {
		return method.isAnnotationPresent(Origin.class);
	}

	@Override
	protected final void checkMethod(Method method) {
		RPCProtocolConfigurationException.checkAndThrow(method,
				m -> String.format("Failed to resolve origin method '%s'(%s), there are the following errors. ",
						m.getName(), m.getDeclaringClass()), RPCProtocolConfigurationException.throwWhen(m -> {
					if (m.isAnnotationPresent(Subscription.class)) {
						return m.getReturnType() != Observable.class;
					}

					return m.getReturnType() != void.class && m.getReturnType() != MonoObservable.class;
				}, String.format(
						"Return type must be `%s` when `@%s` annotation is present, otherwise must be be `void` or `%s`",
						Observable.class.getSimpleName(), Subscription.class.getSimpleName(),
						MonoObservable.class.getSimpleName())),
				RPCProtocolConfigurationException.throwWhen(not(m -> Modifier.isPublic(m.getModifiers())),
						"Must be public"));
	}

	@Override
	protected final String getPath(Method method) {
		Class<?> declaringClass = method.getDeclaringClass();
		Origin classAnnotation = declaringClass.getAnnotation(Origin.class);
		Origin methodAnnotation = method.getAnnotation(Origin.class);

		String classPath = classAnnotation.value();

		if (classPath.isBlank()) {
			throw new RPCProtocolConfigurationException(
					String.format("@%s value on class must be non empty. (%s)", Origin.class.getSimpleName(),
							declaringClass));
		}

		String methodPath = methodAnnotation.value().isEmpty() ? method.getName() : methodAnnotation.value();
		return classPath + ":" + methodPath;
	}

	@Override
	protected final OriginMethod createServiceMethod(String path, Method method, List<Param> simpleParams,
													 List<ExtraParam> extraParams) {
		return new OriginMethod(path, method, simpleParams, extraParams, constructReturnType(method),
				constructReturnJavaType(method), requestDataType(simpleParams));
	}

	private ReturnType constructReturnType(Method method) {
		Class<?> returnType = method.getReturnType();
		if (returnType == void.class) {
			return ReturnType.VOID;
		}
		else if (returnType == MonoObservable.class) {
			Type genericReturnType = method.getGenericReturnType();

			if (genericReturnType instanceof Class) { // Observable
				return ReturnType.ASYNC_VALUE;
			}
			else if (genericReturnType instanceof ParameterizedType) { // Observable<...>
				Type argument = ((ParameterizedType) genericReturnType).getActualTypeArguments()[0]; // <...>
				if (argument instanceof Class) {
					Class<?> classType = (Class<?>) argument;
					if (classType == Response.class) {
						return ReturnType.ASYNC_RESPONSE;
					}
				}
				else if (argument instanceof ParameterizedType) {
					Type rawType = ((ParameterizedType) argument).getRawType();
					if (rawType == Response.class) {
						return ReturnType.ASYNC_RESPONSE;
					}
				}

				return ReturnType.ASYNC_VALUE;
			}

			return ReturnType.ASYNC_RESPONSE;
		}
		else if (method.isAnnotationPresent(Subscription.class)) {
			return ReturnType.SUBSCRIPTION;
		}
		else {
			throw new IllegalStateException(returnType.getName());
		}
	}

	private JavaType constructReturnJavaType(Method method) {
		TypeFactory typeFactory = new ObjectMapper().getTypeFactory();
		Type returnType = method.getGenericReturnType();
		if (returnType instanceof Class) { // Observable
			return typeFactory.constructType(Object.class);
		}
		else if (returnType instanceof ParameterizedType) { // Observable<...>
			Type argument = ((ParameterizedType) returnType).getActualTypeArguments()[0]; // <...>

			if (argument instanceof Class) {
				Class<?> classType = (Class<?>) argument;
				if (classType == Response.class) {
					return typeFactory.constructType(Object.class);
				}
			}
			else if (argument instanceof ParameterizedType) {
				ParameterizedType parameterizedType = (ParameterizedType) argument;
				Type rawType = parameterizedType.getRawType();
				if (rawType == Response.class) {
					Type realArgument = parameterizedType.getActualTypeArguments()[0];
					return typeFactory.constructType(realArgument);
				}
			}

			return typeFactory.constructType(argument);
		}
		else {
			throw new IllegalStateException(returnType.getTypeName());
		}
	}

	private RequestDataType requestDataType(List<Param> simpleParams) {
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
