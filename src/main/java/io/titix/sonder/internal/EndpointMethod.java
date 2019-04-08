package io.titix.sonder.internal;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author tix32 on 24-Feb-19
 */
public final class EndpointMethod
		extends ServiceMethod {

	EndpointMethod(String path, Class<?> clazz, Method method, List<Param> simpleParams, List<ExtraParam> extraParams) {
		super(path, clazz, method, simpleParams, extraParams);
	}

	public Object invoke(Object instance, Object[] args, Function<Class<? extends Annotation>, Object> extraArgResolver) {
		Object[] allArgs = appendExtraArgs(args, extraArgResolver);
		try {
			return method.invoke(instance, allArgs);
		}
		catch (IllegalAccessException | InvocationTargetException e) {
			throw new InternalException(e);
		}
		catch (IllegalArgumentException e) {
			throw illegalEndpointSignature(allArgs);
		}
	}

	private Object[] appendExtraArgs(Object[] simpleArgs, Function<Class<? extends Annotation>, Object> extraArgResolver) {
		List<Param> simpleParams = this.simpleParams;
		List<ExtraParam> extraParams = this.extraParams;

		int simpleArgsLength = simpleArgs.length;

		if (simpleParams.size() != simpleArgsLength) {
			throw illegalEndpointSignature(simpleArgs);
		}

		Object[] allArgs = new Object[simpleParams.size() + extraParams.size()];

		System.arraycopy(simpleArgs, 0, allArgs, 0, simpleArgsLength); // fill simple args

		int extraArgIndex = simpleArgsLength;

		for (ExtraParam extraParam : extraParams) {
			allArgs[extraArgIndex++] = extraArgResolver.apply(extraParam.annotation.annotationType());
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

	@Override
	public String toString() {
		return "EndpointMethod { " +  clazz + ", method - " + method.getName() + ", @Path - " + path + " }";
	}
}
