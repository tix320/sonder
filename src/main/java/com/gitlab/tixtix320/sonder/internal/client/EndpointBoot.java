package com.gitlab.tixtix320.sonder.internal.client;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.gitlab.tixtix320.kiwi.api.check.Try;
import com.gitlab.tixtix320.sonder.api.common.Endpoint;
import com.gitlab.tixtix320.sonder.api.extra.ClientID;
import com.gitlab.tixtix320.sonder.internal.common.ExtraParam;
import com.gitlab.tixtix320.sonder.internal.common.Param;
import com.gitlab.tixtix320.sonder.internal.common.Boot;
import com.gitlab.tixtix320.sonder.internal.common.BootException;
import com.gitlab.tixtix320.sonder.internal.common.EndpointMethod;

public class EndpointBoot extends Boot<EndpointMethod> {

	public EndpointBoot(List<Class<?>> classes) {
		super(classes.stream().filter(clazz -> clazz.isAnnotationPresent(Endpoint.class)).collect(Collectors.toList()));
	}

	@Override
	protected void checkService(Class<?> clazz) {
		BootException.checkAndThrow(clazz,
				aClass -> String.format("Failed to resolve endpoint service(%s), there are the following errors.",
						aClass), BootException.throwWhen(aClass -> Modifier.isAbstract(aClass.getModifiers()) || aClass.isEnum(),
						"Must be a concrete class"),
				BootException.throwWhen(aClass -> (aClass.isMemberClass() && !Modifier.isStatic(aClass.getModifiers())),
						"Must be static, when is a member class"),
				BootException.throwWhen(aClass -> !Modifier.isPublic(aClass.getModifiers()), "Must be public"), BootException
						.throwWhen(
						aClass -> Try.supply(aClass::getConstructor)
								.filter(constructor -> Modifier.isPublic(constructor.getModifiers()))
								.isUseless(), "Must have public no-args constructor"));
	}

	@Override
	protected boolean isServiceMethod(Method method) {
		return method.isAnnotationPresent(Endpoint.class);
	}

	@Override
	protected void checkMethod(Method method) {
		BootException.checkAndThrow(method,
				m -> String.format("Failed to resolve endpoint method '%s'(%s), there are the following errors.",
						m.getName(), m.getDeclaringClass()),
				BootException.throwWhen(m -> !Modifier.isPublic(m.getModifiers()), "Must be public"));
	}

	@Override
	protected String getPath(Method method) {
		return method.getDeclaringClass().getAnnotation(Endpoint.class).value() + ":" + method.getAnnotation(
				Endpoint.class).value();
	}

	@Override
	protected EndpointMethod createServiceMethod(String path, Method method, List<Param> simpleParams,
												 List<ExtraParam> extraParams) {
		return new EndpointMethod(path, method, simpleParams, extraParams);
	}

	@Override
	protected Map<Class<? extends Annotation>, ExtraParamDefinition> getExtraParamDefinitions() {
		return Map.of(ClientID.class, new Boot.ExtraParamDefinition(ClientID.class, Long.class, false));
	}
}
