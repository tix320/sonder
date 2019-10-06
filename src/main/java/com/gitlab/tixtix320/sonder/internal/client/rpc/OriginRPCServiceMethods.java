package com.gitlab.tixtix320.sonder.internal.client.rpc;

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
import com.gitlab.tixtix320.kiwi.api.observable.Observable;
import com.gitlab.tixtix320.sonder.api.common.rpc.Origin;
import com.gitlab.tixtix320.sonder.api.common.rpc.extra.ClientID;
import com.gitlab.tixtix320.sonder.internal.common.StartupException;
import com.gitlab.tixtix320.sonder.internal.common.rpc.extra.ExtraParam;
import com.gitlab.tixtix320.sonder.internal.common.rpc.service.OriginMethod;
import com.gitlab.tixtix320.sonder.internal.common.rpc.service.Param;
import com.gitlab.tixtix320.sonder.internal.common.rpc.service.RPCServiceMethods;

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
						m -> m.getReturnType() != void.class && m.getReturnType() != Observable.class,
						"Return type must be void or Observable"),
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
				getDestination(extraParams), constructResponseType(method));
	}

	@Override
	protected Map<Class<? extends Annotation>, ExtraParamDefinition> getExtraParamDefinitions() {
		return Map.of(ClientID.class, new ExtraParamDefinition(ClientID.class, long.class, false));
	}

	private JavaType constructResponseType(Method method) {
		TypeFactory typeFactory = new ObjectMapper().getTypeFactory();
		Type returnType = method.getGenericReturnType();
		if (returnType instanceof Class) { // Observable
			return typeFactory.constructSimpleType(Object.class, new JavaType[0]);
		}
		else if (returnType instanceof ParameterizedType) { // Observable<...>
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

	private OriginMethod.Destination getDestination(List<ExtraParam> extraParams) {
		for (ExtraParam extraParam : extraParams) {
			if (extraParam.getAnnotation().annotationType() == ClientID.class) {
				return OriginMethod.Destination.CLIENT;
			}
		}
		return OriginMethod.Destination.SERVER;
	}
}
