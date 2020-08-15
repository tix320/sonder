package com.github.tix320.sonder.internal.common.rpc.protocol;

public interface EndpointFactory<T> {

	T create(Class<T> clazz);
}
