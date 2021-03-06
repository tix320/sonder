package com.github.tix320.sonder.api.common.rpc.extra;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

import com.github.tix320.sonder.api.common.communication.Headers;

/**
 * @author Tigran Sargsyan on 23-Mar-20.
 */
public interface EndpointExtraArgInjector<A extends Annotation, T> {

	ExtraParamDefinition<A, T> getParamDefinition();

	T extract(Method method, A annotation, Headers headers);
}
