package com.github.tix320.sonder.internal.common.rpc.service;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.WrongMethodTypeException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import com.github.tix320.sonder.internal.common.rpc.exception.MethodInvocationException;
import com.github.tix320.sonder.internal.common.rpc.extra.ExtraParam;

/**
 * @author tix32 on 24-Feb-19
 */
public final class EndpointMethod extends ServiceMethod {

	private static final Lookup LOOKUP = MethodHandles.publicLookup();

	private final MethodHandle methodHandle;

	private final ResultType resultType;

	public EndpointMethod(String path, Method method, List<Param> simpleParams, List<ExtraParam> extraParams,
						  ResultType resultType) {
		super(path, method, simpleParams, extraParams);
		this.resultType = resultType;
		try {
			method.setAccessible(true);
			this.methodHandle = LOOKUP.unreflect(method);
		}
		catch (IllegalAccessException e) {
			throw new IllegalStateException(String.format("Cannot access to %s, you must open your module.", method),
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
			throw new MethodInvocationException(illegalEndpointSignatureErrorMessage(args), e);
		}
		catch (Throwable throwable) {
			throw new MethodInvocationException(throwable);
		}
	}

	private String illegalEndpointSignatureErrorMessage(Object[] args) {
		return String.format("Illegal signature of method '%s'(%s). Expected following parameters %s",
				getRawMethod().getName(), getRawClass(), Arrays.stream(args)
						.map(arg -> arg.getClass().getSimpleName())
						.collect(Collectors.joining(",", "[", "]")));
	}

	public enum ResultType {
		VOID,
		OBJECT,
		BINARY,
		TRANSFER,
		SUBSCRIPTION
	}
}
