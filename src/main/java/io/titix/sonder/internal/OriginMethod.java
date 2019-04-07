package io.titix.sonder.internal;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;

/**
 * @author tix32 on 24-Feb-19
 */
public final class OriginMethod extends ServiceMethod {

	public final boolean needResponse;

	public final Destination destination;

	OriginMethod(String path, Class<?> clazz, Method method, List<Param> simpleParams, List<ExtraParam> extraParams,
				 boolean needResponse, Destination destination) {
		super(path, clazz, method, simpleParams, extraParams);
		this.needResponse = needResponse;
		this.destination = destination;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		if (!super.equals(o)) return false;
		OriginMethod that = (OriginMethod) o;
		return needResponse == that.needResponse;
	}

	@Override
	public int hashCode() {
		return Objects.hash(super.hashCode(), needResponse);
	}

	@Override
	public String toString() {
		return "OriginSignature{" + "needResponse=" + needResponse + ", path='" + path + '\'' + ", clazz=" + clazz + ", method=" + method + ", simpleParams=" + simpleParams + ", extraParams=" + extraParams + '}';
	}

	public enum Destination {
		CLIENT, SERVER
	}
}
