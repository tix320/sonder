package io.titix.sonder.internal.boot;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.stream.Collectors;

import io.titix.kiwi.rx.Observable;
import io.titix.sonder.Origin;
import io.titix.sonder.Response;
import io.titix.sonder.internal.Config;

import static io.titix.sonder.internal.boot.BootException.check;
import static java.util.Objects.isNull;

/**
 * @author tix32 on 24-Feb-19
 */
public final class OriginBoot extends Boot<OriginSignature> {

	public OriginBoot(String[] packages) {
		super(Config.getPackageClasses(packages).stream()
				.filter(clazz -> clazz.isAnnotationPresent(Origin.class)).collect(Collectors.toList()));
	}

	@Override
	Map<Class<? extends Annotation>, Boot.ExtraParamInfo> getAllowedExtraParams() {
		return Map.of();
	}

	@Override
	void checkService(Class<?> clazz) {
		BootException.checkAndThrow("Failed to resolve origin service " + clazz.getSimpleName() + ", there are the following errors.",
				check(() -> !clazz.isInterface(), "Must be interface"),
				check(() -> !Modifier.isPublic(clazz.getModifiers()), "Must be public"));
	}

	@Override
	boolean isServiceMethod(Method method) {
		boolean annotationPresent = method.isAnnotationPresent(Origin.class);
		if (annotationPresent) {
			return true;
		}
		else if (Modifier.isAbstract(method.getModifiers())) {
			throw new BootException("Abstract method '" + method.getName() + "' in " + method.getDeclaringClass() + " must be origin");
		}
		else {
			return false;
		}
	}

	@Override
	void checkMethod(Method method) {
		BootException.checkAndThrow("Failed to resolve origin method '" + method.getName() + "' in " + method.getDeclaringClass()
						.getName() + ", there are the following errors.",
				check(() -> method.getReturnType() != void.class && !isReturnsObservable(method), "Return type must be void or Observable"),
				check(() -> needResponse(method) ^ isReturnsObservable(method), "Return type must be Observable, when response is needed"),
				check(() -> !Modifier.isPublic(method.getModifiers()), "Must be public"),
				check(() -> Modifier.isStatic(method.getModifiers()), "Must be non static"));
	}

	@Override
	String getPath(Method method) {
		return method.getDeclaringClass().getAnnotation(Origin.class).value()
				+ ":"
				+ method.getAnnotation(Origin.class).value();
	}

	@Override
	OriginSignature createSignature(Method method) {
		return new OriginSignature(getPath(method), method.getDeclaringClass(), method, getParams(method, getAllowedExtraParams()), needResponse(method));
	}

	public Map<Class<?>, Object> createServices(InvocationHandler invocationHandler) {
		return services.stream()
				.collect(Collectors.toMap(
						service -> service,
						service -> Proxy.newProxyInstance(service.getClassLoader(), new Class[]{service}, invocationHandler)));
	}

	private boolean isReturnsObservable(Method method) {
		return method.getReturnType() == Observable.class;
	}

	private boolean needResponse(Method method) {
		Response annotation = method.getAnnotation(Response.class);
		if (isNull(annotation)) {
			annotation = method.getDeclaringClass().getAnnotation(Response.class);
			if (isNull(annotation)) {
				return true;
			}
			else {
				return annotation.value();
			}
		}
		else {
			return annotation.value();
		}
	}
}
