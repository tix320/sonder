package io.titix.sonder.internal.boot;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.stream.Collectors;

import io.titix.kiwi.check.Try;
import io.titix.sonder.Endpoint;
import io.titix.sonder.OriginId;
import io.titix.sonder.internal.Config;

import static io.titix.sonder.internal.boot.BootException.throwWhen;

/**
 * @author tix32 on 24-Feb-19
 */
public final class EndpointBoot extends Boot<EndpointSignature> {

	public EndpointBoot(String[] packages) {
		super(Config.getPackageClasses(packages)
				.stream()
				.filter(clazz -> clazz.isAnnotationPresent(Endpoint.class))
				.collect(Collectors.toList()));
	}

	@Override
	void checkService(Class<?> clazz) {
		BootException.checkAndThrow(clazz,
				aClass -> "Failed to resolve endpoint service " + aClass.getSimpleName() + ", there are the following errors.",
				throwWhen(aClass -> aClass.isInterface() || aClass.isEnum(), "Must be a non abstract class"),
				throwWhen(aClass -> Modifier.isAbstract(clazz.getModifiers()), "Must be a concrete class"),
				throwWhen(aClass -> (clazz.isMemberClass() && !Modifier.isStatic(clazz.getModifiers())),
						"Must be static, when is a member class"),
				throwWhen(aClass -> !Modifier.isPublic(clazz.getModifiers()), "Must be public"),
				throwWhen(aClass -> Try.supply(clazz::getConstructor)
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
				m -> "Failed to resolve endpoint method '" + method.getName() + "' in " + method.getDeclaringClass() + ", there are the following errors.",
				throwWhen(m -> !Modifier.isPublic(method.getModifiers()), "Must be public"));
	}

	@Override
	String getPath(Method method) {
		return method.getDeclaringClass().getAnnotation(Endpoint.class).value() + ":" + method.getAnnotation(
				Endpoint.class).value();
	}

	@Override
	Map<Class<? extends Annotation>, ExtraParamInfo> getAllowedExtraParams() {
		return Map.of(OriginId.class, new ExtraParamInfo(Long.class, "client-id"));
	}

	@Override
	EndpointSignature createSignature(Method method) {
		return new EndpointSignature(getPath(method), method.getDeclaringClass(), method,
				getParams(method, getAllowedExtraParams()));
	}
}
