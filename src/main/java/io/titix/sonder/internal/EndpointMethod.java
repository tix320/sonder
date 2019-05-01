package io.titix.sonder.internal;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author tix32 on 24-Feb-19
 */
public final class EndpointMethod extends ServiceMethod {

	EndpointMethod(String path, Class<?> clazz, Method method, List<Param> simpleParams, List<ExtraParam> extraParams) {
		super(path, clazz, method, simpleParams, extraParams);
	}

	public Object invoke(Object instance, Object[] args) {
		try {
			return method.invoke(instance, args);
		}
		catch (IllegalAccessException | InvocationTargetException e) {
			throw new InternalException(e);
		}
		catch (IllegalArgumentException e) {
			throw illegalEndpointSignature(args);
		}
	}

	private BootException illegalEndpointSignature(Object[] args) {
		return new BootException(String.format("Illegal signature of method '%s'(%s). Expected following parameters %s",
				method.getName(), clazz, Arrays.stream(args)
						.map(arg -> arg.getClass().getSimpleName())
						.collect(Collectors.joining(",", "[", "]"))));
	}

	@Override
	public String toString() {
		return "EndpointMethod { " + clazz + ", method - " + method.getName() + ", @Path - " + path + " }";
	}
}
