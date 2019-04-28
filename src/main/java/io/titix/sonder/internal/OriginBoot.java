package io.titix.sonder.internal;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.gitlab.tixtix320.kiwi.observable.Observable;
import io.titix.sonder.Origin;
import io.titix.sonder.extra.ClientID;

import static io.titix.sonder.internal.BootException.throwWhen;
import static java.util.function.Predicate.not;

/**
 * @author tix32 on 24-Feb-19
 */
public final class OriginBoot extends Boot<OriginMethod> {

	public OriginBoot(List<Class<?>> classes) {
		super(classes.stream().filter(clazz -> clazz.isAnnotationPresent(Origin.class)).collect(Collectors.toList()));
	}

	@Override
	Set<Class<? extends Annotation>> getAllowedExtraParams() {
		return Set.of(ClientID.class);
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
		if (method.isAnnotationPresent(Origin.class)) {
			return true;
		}
		if (Modifier.isAbstract(method.getModifiers())) {
			throw new BootException(
					String.format("Abstract method '%s' in '%s' must be annotated by @%s", method.getName(),
							method.getDeclaringClass(), Origin.class.getSimpleName()));
		}
		else {
			return false;
		}
	}

	@Override
	void checkMethod(Method method) {
		BootException.checkAndThrow(method,
				m -> String.format("Failed to resolve endpoint method '%s'(%s), there are the following errors. ",
						m.getName(), m.getDeclaringClass()),
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
	OriginMethod createSignature(Method method) {
		return new OriginMethod(getPath(method), method.getDeclaringClass(), method, getSimpleParams(method),
				getExtraParams(method), needResponse(method), getDestination(method));
	}

	private boolean needResponse(Method method) {
		return method.getReturnType() != void.class;
	}

	private OriginMethod.Destination getDestination(Method method) {
		for (Annotation[] parameterAnnotations : method.getParameterAnnotations()) {
			for (Annotation annotation : parameterAnnotations) {
				if (annotation.annotationType() == ClientID.class) {
					return OriginMethod.Destination.CLIENT;
				}
			}
		}
		return OriginMethod.Destination.SERVER;
	}
}
