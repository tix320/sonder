package com.gitlab.tixtix320.sonder.internal.client;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.gitlab.tixtix320.kiwi.api.check.Try;
import com.gitlab.tixtix320.sonder.api.common.Endpoint;
import com.gitlab.tixtix320.sonder.api.common.extra.ClientID;
import com.gitlab.tixtix320.sonder.internal.common.StartupException;
import com.gitlab.tixtix320.sonder.internal.common.extra.ExtraParam;
import com.gitlab.tixtix320.sonder.internal.common.service.EndpointMethod;
import com.gitlab.tixtix320.sonder.internal.common.service.Param;
import com.gitlab.tixtix320.sonder.internal.common.service.ServiceMethods;

public class EndpointServiceMethods extends ServiceMethods<EndpointMethod> {

	public EndpointServiceMethods(List<Class<?>> classes) {
		super(classes.stream().filter(clazz -> clazz.isAnnotationPresent(Endpoint.class)).collect(Collectors.toList()));
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
		return new EndpointMethod(path, method, simpleParams, extraParams);
	}

	@Override
	protected Map<Class<? extends Annotation>, ExtraParamDefinition> getExtraParamDefinitions() {
		return Map.of(ClientID.class, new ServiceMethods.ExtraParamDefinition(ClientID.class, Long.class, false));
	}
}
