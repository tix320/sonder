package io.titix.sonder.internal;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.titix.kiwi.check.Try;
import io.titix.sonder.Endpoint;
import io.titix.sonder.extra.ClientID;

import static io.titix.sonder.internal.BootException.throwWhen;

/**
 * @author tix32 on 24-Feb-19
 */
public final class EndpointBoot extends Boot<EndpointMethod> {

	public EndpointBoot(List<Class<?>> classes) {
		super(classes.stream().filter(clazz -> clazz.isAnnotationPresent(Endpoint.class)).collect(Collectors.toList()));
	}

	@Override
	void checkService(Class<?> clazz) throws BootException {
		BootException.checkAndThrow(clazz,
				aClass -> "Failed to resolve endpoint service " + aClass.getSimpleName() + ", there are the following errors.",
				throwWhen(aClass -> aClass.isInterface() || aClass.isEnum(), "Must be a non abstract class"),
				throwWhen(aClass -> Modifier.isAbstract(aClass.getModifiers()), "Must be a concrete class"),
				throwWhen(aClass -> (aClass.isMemberClass() && !Modifier.isStatic(aClass.getModifiers())),
						"Must be static, when is a member class"),
				throwWhen(aClass -> !Modifier.isPublic(aClass.getModifiers()), "Must be public"), throwWhen(
						aClass -> Try.supply(aClass::getConstructor)
								.filter(constructor -> Modifier.isPublic(constructor.getModifiers()))
								.isUseless(), "Must have public no-args constructor"));
	}

	@Override
	boolean isServiceMethod(Method method) {
		return method.isAnnotationPresent(Endpoint.class);
	}

	@Override
	void checkMethod(Method method) {
		BootException.checkAndThrow(method,
				m -> String.format("Failed to resolve endpoint method '%s'(%s), there are the following errors. ",
						m.getName(), m.getDeclaringClass()),
				throwWhen(m -> !Modifier.isPublic(m.getModifiers()), "Must be public"));
	}

	@Override
	String getPath(Method method) {
		return method.getDeclaringClass().getAnnotation(Endpoint.class).value() + ":" + method.getAnnotation(
				Endpoint.class).value();
	}

	@Override
	Map<Class<? extends Annotation>, ExtraParamInfo> getAllowedExtraParams() {
		return Map.of(ClientID.class, new ExtraParamInfo(long.class, "client-id"));
	}

	@Override
	EndpointMethod createSignature(Method method) {
		return new EndpointMethod(getPath(method), method.getDeclaringClass(), method, getSimpleParams(method),
				getExtraParams(method));
	}
}
