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

	abstract Set<Class<? extends Annotation>> getAllowedExtraParams();

	abstract void checkService(Class<?> clazz);

	abstract boolean isServiceMethod(Method method);

	abstract void checkMethod(Method method);

	private void checkExtraParams(Method method, Set<Class<? extends Annotation>> allowedExtraParams) {
		Parameter[] parameters = method.getParameters();
		for (Parameter parameter : parameters) {
			boolean extraParamExists = false;
			for (Annotation annotation : parameter.getAnnotations()) {
				if (annotation.annotationType().isAnnotationPresent(ExtraParamQualifier.class)) {
					if (!allowedExtraParams.contains(annotation.annotationType())) {
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
		Set<Class<? extends Annotation>> allowedExtraParams = getAllowedExtraParams();
		List<Param> params = new ArrayList<>();
		Parameter[] parameters = method.getParameters();

		eachParam:
		for (int i = 0; i < parameters.length; i++) {
			Parameter parameter = parameters[i];
			for (Annotation annotation : parameter.getAnnotations()) {
				if (allowedExtraParams.contains(annotation.annotationType())) {
					break eachParam;
				}
			}
			params.add(new Param(i, parameter.getType()));
		}
		return params;
	}

	List<ExtraParam> getExtraParams(Method method) {
		Set<Class<? extends Annotation>> allowedExtraParams = getAllowedExtraParams();
		List<ExtraParam> params = new ArrayList<>();
		Parameter[] parameters = method.getParameters();

		for (int i = parameters.length - 1; i >= 0; i--) {
			Parameter parameter = parameters[i];
			for (Annotation annotation : parameter.getAnnotations()) {

				if (allowedExtraParams.contains(annotation.annotationType())) {
					params.add(new ExtraParam(i, parameter.getType(), annotation));
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
}
