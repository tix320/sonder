package io.titix.sonder.internal;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

import io.titix.kiwi.check.Try;
import io.titix.sonder.Endpoint;
import io.titix.sonder.OriginId;

import static io.titix.sonder.internal.BootException.check;

/**
 * @author Tigran.Sargsyan on 13-Dec-18
 */
final class EndpointHolder extends Holder {

	private final Map<String, Signature> signatureByPaths;

	EndpointHolder(Collection<Class<?>> services) {
		super(services);
		signatureByPaths = createSignaturesByPaths(signatures);
	}

	Signature getSignature(String s) {
		return signatureByPaths.get(s);
	}

	@Override
	void checkService(Class<?> clazz) {
		BootException.checkAndThrow("Failed to resolve endpoint service " + clazz.getSimpleName() + ", there are the following errors.",
				check(clazz::isInterface, "Must be a non abstract class"),
				check(clazz::isEnum, "Must be a non abstract class"),
				check(() -> Modifier.isAbstract(clazz.getModifiers()), "Must be a concrete class"),
				check(() -> (clazz.isMemberClass() && !Modifier.isStatic(clazz.getModifiers())), "Must be static, when is a member class"),
				check(() -> !Modifier.isPublic(clazz.getModifiers()), "Must be public"),
				check(() -> Try.supply(clazz::getConstructor)
						.filter(constructor -> Modifier.isPublic(constructor.getModifiers()))
						.isUseless(), "Must have public no-args constructor"));
	}

	@Override
	boolean isServiceMethod(Method method) {
		return method.isAnnotationPresent(Endpoint.class);
	}

	@Override
	void checkMethod(Method method) {
		BootException.checkAndThrow("Failed to resolve endpoint method '" + method.getName() + "' in " + method.getDeclaringClass() + ", there are the following errors.",
				check(() -> !Modifier.isPublic(method.getModifiers()), "Must be public"));
	}

	@Override
	String getPath(Method method) {
		return method.getDeclaringClass().getAnnotation(Endpoint.class).value()
				+ ":"
				+ method.getAnnotation(Endpoint.class).value();
	}

	@Override
	Map<Class<? extends Annotation>, ExtraParamInfo> getAllowedExtraParams() {
		return Map.of(OriginId.class, new ExtraParamInfo(Long.class, "client-id"));
	}

	private Map<String, Signature> createSignaturesByPaths(Collection<Signature> signatures) {
		return signatures.stream().collect(Collectors.toMap(signature -> signature.path, signature -> signature));
	}
}
