package com.github.tix320.sonder.internal.common.rpc.service;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;

import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.sonder.api.common.communication.Transfer;
import com.github.tix320.sonder.api.common.rpc.Endpoint;
import com.github.tix320.sonder.api.common.rpc.Subscription;
import com.github.tix320.sonder.api.common.rpc.extra.ExtraParamDefinition;
import com.github.tix320.sonder.internal.common.rpc.exception.RPCProtocolConfigurationException;
import com.github.tix320.sonder.internal.common.rpc.extra.ExtraParam;
import com.github.tix320.sonder.internal.common.rpc.service.EndpointMethod.ResultType;

public final class EndpointRPCMethodResolver extends RPCMethodResolver<EndpointMethod> {

	public EndpointRPCMethodResolver(Set<Class<?>> classes, List<ExtraParamDefinition<?, ?>> extraParamDefinitions) {
		super(classes, extraParamDefinitions);
	}

	@Override
	protected final boolean isServiceMethod(Method method) {
		return method.isAnnotationPresent(Endpoint.class);
	}

	@Override
	protected final void checkMethod(Method method) {
		RPCProtocolConfigurationException.checkAndThrow(method,
				m -> String.format("Failed to resolve endpoint method '%s'(%s), there are the following errors.",
						m.getName(), m.getDeclaringClass()),
				RPCProtocolConfigurationException.throwWhen(m -> !Modifier.isPublic(m.getModifiers()),
						"Must be public"), RPCProtocolConfigurationException.throwWhen(
						m -> m.isAnnotationPresent(Subscription.class) && m.getReturnType() != Observable.class,
						String.format("Return type must be `%s` when `@%s` annotation is present",
								Observable.class.getSimpleName(), Subscription.class.getSimpleName())));
	}

	@Override
	protected final String getPath(Method method) {
		Class<?> declaringClass = method.getDeclaringClass();
		Endpoint classAnnotation = declaringClass.getAnnotation(Endpoint.class);
		Endpoint methodAnnotation = method.getAnnotation(Endpoint.class);

		String classPath = classAnnotation.value();

		if (classPath.isBlank()) {
			throw new RPCProtocolConfigurationException(
					String.format("@%s value on class must be non empty. (%s)", Endpoint.class.getSimpleName(),
							declaringClass));
		}

		String methodPath = methodAnnotation.value().isEmpty() ? method.getName() : methodAnnotation.value();
		return classPath + ":" + methodPath;
	}

	@Override
	protected final EndpointMethod createServiceMethod(String path, Method method, List<Param> simpleParams,
													   List<ExtraParam> extraParams) {
		return new EndpointMethod(path, method, simpleParams, extraParams, resultType(method));
	}

	private ResultType resultType(Method method) {
		Class<?> returnType = method.getReturnType();
		if (returnType == void.class) {
			return ResultType.VOID;
		}
		else if (returnType == byte[].class) {
			return ResultType.BINARY;
		}
		else if (returnType == Transfer.class) {
			return ResultType.TRANSFER;
		}
		else if (method.isAnnotationPresent(Subscription.class)) {
			return ResultType.SUBSCRIPTION;
		}
		else {
			return ResultType.OBJECT;
		}
	}
}
