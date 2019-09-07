package io.titix.sonder.server.internal;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.gitlab.tixtix320.kiwi.api.check.Try;
import io.titix.sonder.Endpoint;
import io.titix.sonder.extra.ClientID;
import io.titix.sonder.internal.EndpointMethod;
import io.titix.sonder.internal.ExtraParam;
import io.titix.sonder.internal.Param;
import io.titix.sonder.internal.boot.Boot;
import io.titix.sonder.internal.boot.BootException;

import static io.titix.sonder.internal.boot.BootException.throwWhen;

public class EndpointBoot extends Boot<EndpointMethod> {

	public EndpointBoot(List<Class<?>> classes) {
		super(classes.stream().filter(clazz -> clazz.isAnnotationPresent(Endpoint.class)).collect(Collectors.toList()));
	}

	@Override
	protected void checkService(Class<?> clazz) {
		BootException.checkAndThrow(clazz,
				aClass -> String.format("Failed to resolve endpoint service(%s), there are the following errors.",
						aClass), throwWhen(aClass -> Modifier.isAbstract(aClass.getModifiers()) || aClass.isEnum(),
						"Must be a concrete class"),
				throwWhen(aClass -> (aClass.isMemberClass() && !Modifier.isStatic(aClass.getModifiers())),
						"Must be static, when is a member class"),
				throwWhen(aClass -> !Modifier.isPublic(aClass.getModifiers()), "Must be public"), throwWhen(
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
				throwWhen(m -> !Modifier.isPublic(m.getModifiers()), "Must be public"));
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
	protected Map<Class<? extends Annotation>, Boot.ExtraParamDefinition> getExtraParamDefinitions() {
		return Map.of(ClientID.class, new Boot.ExtraParamDefinition(ClientID.class, long.class, false));
	}
}
