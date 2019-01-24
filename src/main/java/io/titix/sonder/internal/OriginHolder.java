package io.titix.sonder.internal;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

import io.titix.sonder.Callback;
import io.titix.sonder.Origin;
import io.titix.sonder.Response;

import static io.titix.sonder.internal.BootException.check;
import static java.util.Objects.isNull;

/**
 * @author Tigran.Sargsyan on 13-Dec-18
 */
final class OriginHolder extends Holder {

	private final Map<Method, Signature> signatureByMethods;

	OriginHolder(Collection<Class<?>> services) {
		super(services);
		signatureByMethods = createSignaturesByMethods(signatures);
	}

	Signature getSignature(Method s) {
		return signatureByMethods.get(s);
	}

	@Override
	Map<Class<? extends Annotation>, ExtraParamInfo> getAllowedExtraParams() {
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
		return method.isAnnotationPresent(Origin.class);
	}

	@Override
	void checkMethod(Method method) {
		BootException.checkAndThrow("Failed to resolve origin method '" + method.getName() + "' in " + method.getDeclaringClass()
						.getName() + ", there are the following errors.",
				check(() -> method.getReturnType() != void.class, "Return type must be void"),
				check(() -> needResponse(method) ^ hasAsyncParam(method), "Last parameter must be Callback, when response is needed"),
				check(() -> !Modifier.isPublic(method.getModifiers()), "Must be public"),
				check(() -> Modifier.isStatic(method.getModifiers()), "Must be non static"));
	}

	@Override
	String getPath(Method method) {
		return method.getDeclaringClass().getAnnotation(Origin.class).value()
				+ ":"
				+ method.getAnnotation(Origin.class).value();
	}

	private Map<Method, Signature> createSignaturesByMethods(Collection<Signature> signatures) {
		return signatures.stream().collect(Collectors.toMap(signature -> signature.method, signature -> signature));
	}

	private boolean hasAsyncParam(Method method) {
		Class<?>[] parameterTypes = method.getParameterTypes();
		return parameterTypes.length != 0 && parameterTypes[parameterTypes.length - 1] == Callback.class;
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
