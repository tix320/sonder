package com.gitlab.tixtix320.sonder.internal.common;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.WrongMethodTypeException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import com.gitlab.tixtix320.kiwi.api.check.Try;

/**
 * @author tix32 on 24-Feb-19
 */
public final class EndpointMethod extends ServiceMethod {

	private final MethodHandle methodHandle;

	public EndpointMethod(String path, Method method, List<Param> simpleParams, List<ExtraParam> extraParams) {
		super(path, method, simpleParams, extraParams);
		this.methodHandle = Try.supply(() -> MethodHandles.publicLookup().unreflect(method))
				.getOrElseThrow(InternalException::new)
				.orElseThrow();
	}

	public Object invoke(Object instance, Object[] args) {
		try {
			return methodHandle.bindTo(instance).invokeWithArguments(args);
		}
		catch (WrongMethodTypeException e) {
			throw illegalEndpointSignature(args);
		}
		catch (Throwable throwable) {
			throw new InternalException(throwable);
		}
	}

	private BootException illegalEndpointSignature(Object[] args) {
		return new BootException(String.format("Illegal signature of method '%s'(%s). Expected following parameters %s",
				getRawMethod().getName(), getRawClass(), Arrays.stream(args)
						.map(arg -> arg.getClass().getSimpleName())
						.collect(Collectors.joining(",", "[", "]"))));
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		if (!super.equals(o)) return false;
		EndpointMethod that = (EndpointMethod) o;
		return methodHandle.equals(that.methodHandle);
	}

	@Override
	public int hashCode() {
		return Objects.hash(super.hashCode(), methodHandle);
	}

	@Override
	public String toString() {
		return "EndpointMethod{" + "methodHandle=" + methodHandle + ", path='" + path + '\'' + ", rawMethod=" + rawMethod + ", simpleParams=" + simpleParams + ", extraParams=" + extraParams + '}';
	}
}
