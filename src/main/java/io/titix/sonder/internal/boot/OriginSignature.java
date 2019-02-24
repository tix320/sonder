package io.titix.sonder.internal.boot;

import java.lang.reflect.Method;
import java.util.List;

/**
 * @author tix32 on 24-Feb-19
 */
public final class OriginSignature extends Signature {

	public final boolean needResponse;

	OriginSignature(String path, Class<?> clazz, Method method, List<Param> params, boolean needResponse) {
		super(path, clazz, method, params);
		this.needResponse = needResponse;
	}
}
