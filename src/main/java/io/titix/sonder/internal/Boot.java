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
public abstract class Boot<T extends ServiceMethod> {

	private final List<T> signatures;

	Boot(List<Class<?>> classes) {
		this.signatures = createSignatures(classes);
	}

	public final List<T> getSignatures() {
		return signatures;
	}

	private List<T> createSignatures(Collection<Class<?>> services) {
		List<T> signatures = services.stream()
				.peek(this::checkService)
				.flatMap(clazz -> Arrays.stream(clazz.getDeclaredMethods()))
				.filter(this::isServiceMethod)
				.peek(this::checkMethod)
				.peek(method -> checkExtraParams(method, getAllowedExtraParams()))
				.map(this::createSignature)
				.peek(signature -> checkSignaturePaths(signature.path))
				.collect(Collectors.toUnmodifiableList());
		checkDuplicatePaths(signatures);
		return signatures;
	}

	abstract Map<Class<? extends Annotation>, ExtraParamInfo> getAllowedExtraParams();

	abstract void checkService(Class<?> clazz) throws BootException;

	abstract boolean isServiceMethod(Method method);

	abstract void checkMethod(Method method);

	private void checkExtraParams(Method method, Map<Class<? extends Annotation>, ExtraParamInfo> allowedExtraParams) {
		Parameter[] parameters = method.getParameters();
		for (Parameter parameter : parameters) {
			boolean extraParamExists = false;
			for (Annotation annotation : parameter.getAnnotations()) {
				if (annotation.annotationType().isAnnotationPresent(ExtraParamQualifier.class)) {
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

	List<Param> getSimpleParams(Method method) {
		Map<Class<? extends Annotation>, ExtraParamInfo> allowedExtraParams = getAllowedExtraParams();
		List<Param> params = new ArrayList<>();
		Parameter[] parameters = method.getParameters();

		eachParam:
		for (int i = 0; i < parameters.length; i++) {
			Parameter parameter = parameters[i];
			for (Annotation annotation : parameter.getAnnotations()) {
				if (allowedExtraParams.containsKey(annotation.annotationType())) {
					break eachParam;
				}
			}
			params.add(new Param(i));
		}
		return params;
	}

	List<ExtraParam> getExtraParams(Method method) {
		Map<Class<? extends Annotation>, ExtraParamInfo> allowedExtraParams = getAllowedExtraParams();
		List<ExtraParam> params = new ArrayList<>();
		Parameter[] parameters = method.getParameters();

		for (int i = parameters.length - 1; i >= 0; i--) {
			Parameter parameter = parameters[i];
			for (Annotation annotation : parameter.getAnnotations()) {

				ExtraParamInfo extraParamInfo = allowedExtraParams.get(annotation.annotationType());

				if (extraParamInfo != null) {

					if (extraParamInfo.requiredType != parameter.getType()) {
						throw new BootException(String.format("Extra param @%s must have type %s",
								annotation.annotationType().getSimpleName(), extraParamInfo.requiredType.getName()));
					}
					params.add(new ExtraParam(i, extraParamInfo.key));
				}
			}
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
		Map<String, ServiceMethod> uniqueSignatures = new HashMap<>();
		for (ServiceMethod serviceMethod : signatures) {
			if (uniqueSignatures.containsKey(serviceMethod.path)) {
				ServiceMethod presentServiceMethod = uniqueSignatures.get(serviceMethod.path);
				throw new DuplicatePathException(
						String.format("Methods %s(%s) and %s(%s) has same path", serviceMethod.method.getName(),
								serviceMethod.clazz.getName(), presentServiceMethod.method.getName(),
								presentServiceMethod.clazz.getName()));
			}
			uniqueSignatures.put(serviceMethod.path, serviceMethod);
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
