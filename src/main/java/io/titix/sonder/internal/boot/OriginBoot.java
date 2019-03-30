package io.titix.sonder.internal.boot;

import io.titix.kiwi.rx.Observable;
import io.titix.sonder.Origin;
import io.titix.sonder.internal.Config;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.stream.Collectors;

import static io.titix.sonder.internal.boot.BootException.throwWhen;
import static java.util.function.Predicate.not;

/**
 * @author tix32 on 24-Feb-19
 */
public final class OriginBoot extends Boot<OriginSignature> {

	public OriginBoot(String[] packages) {
		super(Config.getPackageClasses(packages)
				.stream()
				.filter(clazz -> clazz.isAnnotationPresent(Origin.class))
				.collect(Collectors.toList()));
	}

	@Override
	Map<Class<? extends Annotation>, Boot.ExtraParamInfo> getAllowedExtraParams() {
		return Map.of();
	}

	@Override
	void checkService(Class<?> clazz) {
		BootException.checkAndThrow(clazz,
				aClass -> "Failed to resolve origin service " + aClass.getSimpleName() + ", there are the following errors.",
				throwWhen(not(Class::isInterface), "Must be interface"),
				throwWhen(not(aClass -> Modifier.isPublic(aClass.getModifiers())), "Must be public"));
	}

	@Override
	boolean isServiceMethod(Method method) {
		boolean annotationPresent = method.isAnnotationPresent(Origin.class);
		if (annotationPresent) {
			return true;
		}
		else if (Modifier.isAbstract(method.getModifiers())) {
			throw new BootException(
					"Abstract method '" + method.getName() + "' in " + method.getDeclaringClass() + " must be origin");
		}
		else {
			return false;
		}
	}

	@Override
	void checkMethod(Method method) {
		BootException.checkAndThrow(method,
				m -> "Failed to resolve origin method '" + m.getName() + "' in " + m.getDeclaringClass()
						.getName() + ", there are the following errors.",
				throwWhen(m -> m.getReturnType() != void.class && m.getReturnType() != Observable.class,
						"Return type must be void or Observable"),
				throwWhen(not(m -> Modifier.isPublic(m.getModifiers())), "Must be public"),
				throwWhen(m -> Modifier.isStatic(m.getModifiers()), "Must be non static"));
	}

	@Override
	String getPath(Method method) {
		return method.getDeclaringClass().getAnnotation(Origin.class).value() + ":" + method.getAnnotation(Origin.class)
				.value();
	}

	@Override
	OriginSignature createSignature(Method method) {
		return new OriginSignature(getPath(method), method.getDeclaringClass(), method,
				getParams(method, getAllowedExtraParams()), needResponse(method));
	}

	private boolean needResponse(Method method) {
		return method.getReturnType() != void.class;
	}
}
