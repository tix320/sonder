package com.github.tix320.sonder.internal.common.rpc.service;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.WrongMethodTypeException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import com.github.tix320.kiwi.api.util.WrapperException;
import com.github.tix320.sonder.internal.common.rpc.StartupException;
import com.github.tix320.sonder.internal.common.rpc.extra.ExtraParam;

/**
 * @author tix32 on 24-Feb-19
 */
public abstract class EndpointMethod extends ServiceMethod {

	private final MethodHandle methodHandle;

	private final ResultType resultType;

	public EndpointMethod(String path, Method method, List<Param> simpleParams, List<ExtraParam> extraParams,
						  ResultType resultType) {
		super(path, method, simpleParams, extraParams);
		this.resultType = resultType;
		try {
			this.methodHandle = MethodHandles.publicLookup().unreflect(method);
		}
		catch (IllegalAccessException e) {
			throw new IllegalStateException(
					String.format("Cannot access to class %s, you must open your module.", method.getDeclaringClass()),
					e);
		}
	}

	public ResultType resultType() {
		return resultType;
	}

	public Object invoke(Object instance, Object[] args) {
		try {
			return methodHandle.bindTo(instance).invokeWithArguments(args);
		}
		catch (WrongMethodTypeException e) {
			throw illegalEndpointSignature(args);
		}
		catch (Throwable throwable) {
			throw WrapperException.wrap(throwable);
		}
	}

	private StartupException illegalEndpointSignature(Object[] args) {
		return new StartupException(
				String.format("Illegal signature of method '%s'(%s). Expected following parameters %s",
						getRawMethod().getName(), getRawClass(), Arrays.stream(args)
								.map(arg -> arg.getClass().getSimpleName())
								.collect(Collectors.joining(",", "[", "]"))));
	}

	public enum ResultType {
		VOID,
		OBJECT,
		BINARY,
		TRANSFER
	}
}
