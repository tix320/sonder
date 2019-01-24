package io.titix.sonder.internal;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.stream.Collectors;

import io.titix.sonder.Endpoint;
import io.titix.sonder.Origin;

/**
 * @author Tigran.Sargsyan on 13-Dec-18
 */
abstract class Holder {

	private final Collection<Class<?>> services;

	final Collection<Signature> signatures;

	Holder(Collection<Class<?>> services) {
		this.services = services;
		this.signatures = createSignatures(services);
	}

	Collection<Class<?>> getServices() {
		return services;
	}

	private Collection<Signature> createSignatures(Collection<Class<?>> services) {
		Map<Class<? extends Annotation>, ExtraParamInfo> allowedExtraParams = getAllowedExtraParams();
		List<Signature> signatures = services.stream()
				.peek(this::checkService)
				.flatMap(clazz -> Arrays.stream(clazz.getDeclaredMethods()))
				.filter(this::isServiceMethod)
				.peek(this::checkMethod)
				.peek(method -> checkExtraParam(method, allowedExtraParams))
				.map(method -> new Signature(getPath(method), method.getDeclaringClass(), method, getParams(method, allowedExtraParams)))
				.peek(signature -> checkSignaturePaths(signature.path))
				.collect(Collectors.toList());
		checkDuplicatePaths(signatures);
		return signatures;
	}

	abstract Map<Class<? extends Annotation>, ExtraParamInfo> getAllowedExtraParams();

	abstract void checkService(Class<?> clazz);

	abstract boolean isServiceMethod(Method method);

	abstract void checkMethod(Method method);

	private void checkExtraParam(Method method, Map<Class<? extends Annotation>, ExtraParamInfo> allowedExtraParams) {
		Parameter[] parameters = method.getParameters();
		for (Parameter parameter : parameters) {
			boolean extraParamExists = false;
			for (Annotation annotation : parameter.getAnnotations()) {
				if (annotation.annotationType().isAnnotationPresent(ExtraParam.class)) {
					if (!allowedExtraParams.containsKey(annotation.annotationType())) {
						throw new BootException("Extra param @" + annotation.annotationType()
								.getSimpleName() + " is not allowed in method " + method.getName() + " of " + method.getDeclaringClass());
					}
					if (extraParamExists) {
						throw new BootException("Method '" + method.getName() + "' parameter '" + parameter + "' in " + method
								.getDeclaringClass() + " must have only one extra param annotation");
					}
					extraParamExists = true;
				}
			}
		}
	}

	abstract String getPath(Method method);

	private List<Param> getParams(Method method, Map<Class<? extends Annotation>, ExtraParamInfo> allowedExtraParams) {
		List<Param> params = new ArrayList<>();
		Parameter[] parameters = method.getParameters();

		eachParam:
		for (Parameter parameter : parameters) {
			for (Annotation annotation : parameter.getAnnotations()) {
				if (allowedExtraParams.containsKey(annotation.annotationType())) {
					ExtraParamInfo extraParamInfo = allowedExtraParams.get(annotation.annotationType());
					if (!(extraParamInfo.requiredType == parameter.getType())) {
						throw new BootException("Extra param @" + annotation.annotationType()
								.getSimpleName() + " must have type " + extraParamInfo.requiredType);
					}
					params.add(new Param(extraParamInfo.key, true));
					continue eachParam;
				}
			}
			params.add(new Param(parameter.getName(), false));
		}
		return params;
	}

	private void checkSignaturePaths(String path) {
		if (path.equals("")) {
			throw new BootException("path value of @" + Origin.class.getSimpleName() + " or @" + Endpoint.class.getSimpleName() + " must be non empty");
		}
	}

	private void checkDuplicatePaths(Collection<Signature> signatures) {
		Map<String, Signature> uniqueSignatures = new HashMap<>();
		for (Signature signature : signatures) {
			if (uniqueSignatures.containsKey(signature.path)) {
				Signature presentSignature = uniqueSignatures.get(signature.path);
				throw new BootException("Path of following methods are the same + '" + signature.path + "'\n'"
						+ signature.method.getName() + "' in " + signature.clazz + " and '" + presentSignature.method
						.getName() + "' in " + presentSignature.clazz);
			}
			uniqueSignatures.put(signature.path, signature);
		}
	}

	static final class ExtraParamInfo {
		private final Class<?> requiredType;

		private final String key;

		ExtraParamInfo(Class<?> requiredType, String key) {
			this.requiredType = requiredType;
			this.key = key;
		}
	}
}
