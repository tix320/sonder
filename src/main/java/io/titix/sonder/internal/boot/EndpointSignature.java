package io.titix.sonder.internal.boot;

import java.lang.reflect.Method;
import java.util.List;

/**
 * @author tix32 on 24-Feb-19
 */
public final class EndpointSignature extends Signature {

	EndpointSignature(String path, Class<?> clazz, Method method, List<Param> params) {
		super(path, clazz, method, params);
	}
}
