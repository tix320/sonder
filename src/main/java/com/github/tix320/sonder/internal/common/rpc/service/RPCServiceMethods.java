package com.github.tix320.sonder.internal.common.rpc.service;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.github.tix320.sonder.internal.common.rpc.StartupException;
import com.github.tix320.sonder.internal.common.rpc.extra.ExtraParam;
import com.github.tix320.sonder.internal.common.rpc.extra.ExtraParamQualifier;

import static java.util.stream.Collectors.*;

/**
 * @author Tigran.Sargsyan on 13-Dec-18
 */
public abstract class RPCServiceMethods<T extends ServiceMethod> {

	private final List<T> serviceMethods;

	public RPCServiceMethods(List<Class<?>> classes) {
		this.serviceMethods = createServiceMethods(classes);
	}

	public final List<T> get() {
		return serviceMethods;
	}

	private List<T> createServiceMethods(Collection<Class<?>> services) {
		return services.stream()
				.filter(this::isService)
				.peek(this::checkService)
				.flatMap(clazz -> Arrays.stream(clazz.getDeclaredMethods()))
				.filter(this::isServiceMethod)
				.peek(this::checkMethod)
				.map(this::createServiceMethod)
				.peek(this::checkPath)
				.collect(collectingAndThen(toUnmodifiableList(), methods -> {
					checkDuplicatePaths(methods);
					return methods;
				}));
	}

	protected abstract boolean isService(Class<?> clazz);

	protected abstract void checkService(Class<?> clazz);

	protected abstract boolean isServiceMethod(Method method);

	protected abstract void checkMethod(Method method);

	private T createServiceMethod(Method method) {
		Map<String, List<? extends Param>> params = resolveParameters(method);

		@SuppressWarnings("unchecked")
		List<Param> simpleParams = (List<Param>) params.get("simple");

		@SuppressWarnings("unchecked")
		List<ExtraParam> extraParams = (List<ExtraParam>) params.get("extra");

		return createServiceMethod(getPath(method), method, simpleParams, extraParams);
	}

	private Map<String, List<? extends Param>> resolveParameters(Method method) {
		List<Param> simpleParams = new ArrayList<>();
		List<ExtraParam> extraParams = new ArrayList<>();

		Map<Class<? extends Annotation>, ExtraParamDefinition> extraParamDefinitions = getExtraParamDefinitions();
		Parameter[] parameters = method.getParameters();
		TypeFactory typeFactory = new ObjectMapper().getTypeFactory();
		for (int i = 0; i < parameters.length; i++) {
			Parameter parameter = parameters[i];
			boolean extraParamAnnotationExists = false;
			for (Annotation annotation : parameter.getAnnotations()) {
				if (annotation.annotationType().isAnnotationPresent(ExtraParamQualifier.class)) {
					if (!extraParamDefinitions.containsKey(annotation.annotationType())) {
						throw new StartupException(String.format("Extra param @%s is not allowed in method %s(%s)",
								annotation.annotationType().getSimpleName(), method.getName(),
								method.getDeclaringClass()));
					}
					if (extraParamAnnotationExists) {
						throw new StartupException(String.format(
								"Parameter(index:%s) in method %s(%s) must have only one extra param annotation", i,
								method.getName(), method.getDeclaringClass()));
					}

					extraParamAnnotationExists = true;
					ExtraParamDefinition definition = extraParamDefinitions.get(annotation.annotationType());
					if (parameter.getType() != definition.expectedType) {
						throw new StartupException(String.format("Extra param @%s must have type %s in method %s(%s)",
								annotation.annotationType().getSimpleName(), definition.expectedType.getName(),
								method.getName(), method.getDeclaringClass()));
					}
					extraParams.add(
							new ExtraParam(i, typeFactory.constructType(parameter.getParameterizedType()), annotation));
				}
			}
			if (!extraParamAnnotationExists) { // is simple param
				simpleParams.add(new Param(i, typeFactory.constructType(parameter.getParameterizedType())));
			}
		}

		String nonExistingRequiredExtraParams = extraParamDefinitions.values()
				.stream()
				.filter(extraParamDefinition -> extraParamDefinition.isRequired)
				.map(extraParamDefinition -> extraParamDefinition.annotationType)
				.filter(annotationType -> extraParams.stream()
						.noneMatch(extraParam -> extraParam.getAnnotation().annotationType().equals(annotationType)))
				.map(annotation -> "@" + annotation.getSimpleName())
				.collect(joining(",", "[", "]"));

		if (nonExistingRequiredExtraParams.length() > 2) { // is not empty
			throw new StartupException(
					String.format("Extra params %s are required in method %s(%s)", nonExistingRequiredExtraParams,
							method.getName(), method.getDeclaringClass()));
		}

		return Map.of("simple", simpleParams, "extra", extraParams);
	}

	protected abstract String getPath(Method method);

	protected abstract T createServiceMethod(String path, Method method, List<Param> simpleParams,
											 List<ExtraParam> extraParams);

	private void checkPath(ServiceMethod method) {
		if (method.getPath().startsWith(":")) {
			throw new StartupException(String.format("path value must be non empty in %s", method.getPath()));
		}
	}

	private void checkDuplicatePaths(Collection<T> signatures) {
		Map<String, ServiceMethod> uniqueSignatures = new HashMap<>();
		for (ServiceMethod serviceMethod : signatures) {
			if (uniqueSignatures.containsKey(serviceMethod.getPath())) {
				ServiceMethod presentServiceMethod = uniqueSignatures.get(serviceMethod.getPath());
				throw new DuplicatePathException(
						String.format("Methods %s(%s) and %s(%s) has same path", serviceMethod.getRawMethod().getName(),
								serviceMethod.getRawClass().getName(), presentServiceMethod.getRawMethod().getName(),
								presentServiceMethod.getRawClass().getName()));
			}
			uniqueSignatures.put(serviceMethod.getPath(), serviceMethod);
		}
	}

	protected abstract Map<Class<? extends Annotation>, ExtraParamDefinition> getExtraParamDefinitions();

	protected static class ExtraParamDefinition {

		private final Class<? extends Annotation> annotationType;

		private final Class<?> expectedType;

		private final boolean isRequired;

		public ExtraParamDefinition(Class<? extends Annotation> annotationType, Class<?> expectedType,
									boolean isRequired) {
			this.annotationType = annotationType;
			this.expectedType = expectedType;
			this.isRequired = isRequired;
		}
	}
}