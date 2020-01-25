package com.github.tix320.sonder.internal.client.rpc;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.github.tix320.kiwi.api.reactive.observable.MonoObservable;
import com.github.tix320.sonder.api.common.communication.Transfer;
import com.github.tix320.sonder.api.common.rpc.Origin;
import com.github.tix320.sonder.api.common.rpc.extra.ClientID;
import com.github.tix320.sonder.internal.common.rpc.StartupException;
import com.github.tix320.sonder.internal.common.rpc.extra.ExtraParam;
import com.github.tix320.sonder.internal.common.rpc.service.OriginMethod;
import com.github.tix320.sonder.internal.common.rpc.service.OriginMethod.RequestDataType;
import com.github.tix320.sonder.internal.common.rpc.service.Param;
import com.github.tix320.sonder.internal.common.rpc.service.RPCServiceMethods;

import static java.util.function.Predicate.not;

public class OriginRPCServiceMethods extends RPCServiceMethods<OriginMethod> {

	public OriginRPCServiceMethods(List<Class<?>> classes) {
		super(classes);
	}

	@Override
	protected boolean isService(Class<?> clazz) {
		return clazz.isAnnotationPresent(Origin.class);
	}

	@Override
	protected void checkService(Class<?> clazz) {
		StartupException.checkAndThrow(clazz,
				aClass -> String.format("Failed to resolve origin service(%s), there are the following errors.",
						aClass), StartupException.throwWhen(not(Class::isInterface), "Must be interface"),
				StartupException.throwWhen(not(aClass -> Modifier.isPublic(aClass.getModifiers())), "Must be public"));
	}

	@Override
	protected boolean isServiceMethod(Method method) {
		return method.isAnnotationPresent(Origin.class);
	}

	@Override
	protected void checkMethod(Method method) {
		StartupException.checkAndThrow(method,
				m -> String.format("Failed to resolve origin method '%s'(%s), there are the following errors. ",
						m.getName(), m.getDeclaringClass()), StartupException.throwWhen(
						m -> m.getReturnType() != void.class && m.getReturnType() != MonoObservable.class,
						String.format("Return type must be void or %s", MonoObservable.class.getName())),
				StartupException.throwWhen(not(m -> Modifier.isPublic(m.getModifiers())), "Must be public"));
	}

	@Override
	protected String getPath(Method method) {
		return method.getDeclaringClass().getAnnotation(Origin.class).value() + ":" + method.getAnnotation(Origin.class)
				.value();
	}

	@Override
	protected OriginMethod createServiceMethod(String path, Method method, List<Param> simpleParams,
											   List<ExtraParam> extraParams) {
		return new OriginMethod(path, method, simpleParams, extraParams, needResponse(method),
				getDestination(extraParams), constructResponseType(method), requestDataType(simpleParams));
	}

	@Override
	protected Map<Class<? extends Annotation>, ExtraParamDefinition> getExtraParamDefinitions() {
		return Map.of(ClientID.class, new ExtraParamDefinition(ClientID.class, long.class, false));
	}

	private JavaType constructResponseType(Method method) {
		TypeFactory typeFactory = new ObjectMapper().getTypeFactory();
		Type returnType = method.getGenericReturnType();
		if (returnType instanceof Class) { // MonoObservable
			return typeFactory.constructType(Object.class);
		}
		else if (returnType instanceof ParameterizedType) { // MonoObservable<...>
			Type argument = ((ParameterizedType) returnType).getActualTypeArguments()[0]; // <...>
			return typeFactory.constructType(argument);
		}
		else {
			throw new IllegalStateException();
		}
	}

	private boolean needResponse(Method method) {
		return method.getReturnType() != void.class;
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

	private OriginMethod.Destination getDestination(List<ExtraParam> extraParams) {
		for (ExtraParam extraParam : extraParams) {
			if (extraParam.getAnnotation().annotationType() == ClientID.class) {
				return OriginMethod.Destination.CLIENT;
			}
		}
		return OriginMethod.Destination.SERVER;
	}
}
