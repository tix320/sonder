package io.titix.sonder.internal.boot;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.titix.sonder.internal.InternalException;

/**
 * @author tix32 on 24-Feb-19
 */
public final class EndpointSignature extends Signature {

	EndpointSignature(String path, Class<?> clazz, Method method, List<Param> params) {
		super(path, clazz, method, params);
	}

	public Object invoke(Object instance, Object[] args, Function<String, Object> extraArgResolver) {
		Object[] allArgs = appendExtraArgs(args, extraArgResolver);
		try {
			return method.invoke(instance, allArgs);
		}
		catch (IllegalAccessException | InvocationTargetException e) {
			throw new InternalException("Cannot invoke method " + method, e);
		}
		catch (IllegalArgumentException e) {
			throw illegalEndpointSignature(allArgs);
		}
	}

	private Object[] appendExtraArgs(Object[] realArgs, Function<String, Object> extraArgResolver) {
		List<Param> parameters = params;
		if (parameters.stream().filter(param -> !param.isExtra).count() != realArgs.length) {
			throw illegalEndpointSignature(realArgs);
		}
		Object[] allArgs = new Object[parameters.size()];
		int realArgIndex = 0;
		for (int i = 0; i < allArgs.length; i++) {
			Param param = parameters.get(i);
			allArgs[i] = param.isExtra ? extraArgResolver.apply(param.key) : realArgs[realArgIndex++];
		}
		return allArgs;
	}

	private BootException illegalEndpointSignature(Object[] args) {
		return new BootException(
				"Illegal signature of method '" + method.getName() + "' in " + clazz + ". Expected following parameters " + Arrays
						.stream(args)
						.map(arg -> arg.getClass().getSimpleName())
						.collect(Collectors.joining(",", "[", "]")));
	}
}
