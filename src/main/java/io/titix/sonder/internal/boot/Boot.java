package io.titix.sonder.internal.boot;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.stream.Collectors;

import io.titix.sonder.Endpoint;
import io.titix.sonder.Origin;
import io.titix.sonder.internal.ExtraParam;

/**
 * @author Tigran.Sargsyan on 13-Dec-18
 */
public abstract class Boot<T extends Signature> {

	private final List<T> signatures;

	Boot(Collection<Class<?>> services) {
		this.signatures = createSignatures(services);
	}

	public final List<T> getSignatures() {
		return signatures;
	}

	private List<T> createSignatures(Collection<Class<?>> services) {
		List<T> signatures = services.stream()
				.peek(this::checkService)
				.flatMap(clazz -> Arrays.stream(clazz.getDeclaredMethods()))
				.filter(this::isServiceMethod)
				.peek(this::checkMethod).peek(method -> checkExtraParam(method, getAllowedExtraParams()))
				.map(this::createSignature)
				.peek(signature -> checkSignaturePaths(signature.path))
				.collect(Collectors.toUnmodifiableList());
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
						throw new BootException(String.format("Extra param @%s is not allowed in method %s(%s)",
								annotation.annotationType().getSimpleName(), method.getName(),
								method.getDeclaringClass()));
					}
					if (extraParamExists) {
						throw new BootException(String.format(
								"Parameter '%s' in method %s(%s) must have only one extra param annotation",
								parameter.getName(), method.getName(), method.getDeclaringClass().getName()));
					}
					extraParamExists = true;
				}
			}
		}
	}

	abstract String getPath(Method method);

	List<Param> getParams(Method method, Map<Class<? extends Annotation>, ExtraParamInfo> allowedExtraParams) {
		List<Param> params = new ArrayList<>();
		Parameter[] parameters = method.getParameters();

		eachParam:
		for (Parameter parameter : parameters) {
			for (Annotation annotation : parameter.getAnnotations()) {
				if (allowedExtraParams.containsKey(annotation.annotationType())) {
					ExtraParamInfo extraParamInfo = allowedExtraParams.get(annotation.annotationType());
					if (!(extraParamInfo.requiredType == parameter.getType())) {
						throw new BootException(String.format("Extra param @%s must have type %s",
								annotation.annotationType().getSimpleName(), extraParamInfo.requiredType.getName()));
					}
					params.add(new Param(extraParamInfo.key, true));
					continue eachParam;
				}
			}
			params.add(new Param(parameter.getName(), false));
		}
		return params;
	}

	abstract T createSignature(Method method);

	private void checkSignaturePaths(String path) {
		if (path.equals("")) {
			throw new BootException(
					String.format("Path value of @%s or @%s must be non empty", Origin.class.getSimpleName(),
							Endpoint.class.getSimpleName()));
		}
	}

	private void checkDuplicatePaths(Collection<T> signatures) {
		Map<String, Signature> uniqueSignatures = new HashMap<>();
		for (Signature signature : signatures) {
			if (uniqueSignatures.containsKey(signature.path)) {
				Signature presentSignature = uniqueSignatures.get(signature.path);
				throw new DuplicatePathException(
						String.format("Methods %s(%s) and %s(%s) has same path", signature.method.getName(),
								signature.clazz.getName(), presentSignature.method.getName(),
								presentSignature.clazz.getName()));
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
