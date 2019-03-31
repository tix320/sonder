package io.titix.sonder.internal.boot;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;

/**
 * @author tix32 on 24-Feb-19
 */
public final class OriginSignature extends Signature {

	public final boolean needResponse;

	OriginSignature(String path, Class<?> clazz, Method method, List<Param> params, boolean needResponse) {
		super(path, clazz, method, params);
		this.needResponse = needResponse;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		if (!super.equals(o)) return false;
		OriginSignature that = (OriginSignature) o;
		return needResponse == that.needResponse;
	}

	@Override
	public int hashCode() {
		return Objects.hash(super.hashCode(), needResponse);
	}

	@Override
	public String toString() {
		return "OriginSignature{" + "needResponse=" + needResponse + ", path='" + path + '\'' + ", clazz=" + clazz + ", method=" + method + ", params=" + params + '}';
	}
}
