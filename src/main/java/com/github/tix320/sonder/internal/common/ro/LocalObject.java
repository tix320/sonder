package com.github.tix320.sonder.internal.common.ro;

public class LocalObject {
	private final Object object;
	private final Class<?> interfacee;

	public LocalObject(Object object, Class<?> interfacee) {
		this.object = object;
		this.interfacee = interfacee;
	}

	public Object getObject() {
		return object;
	}

	public Class<?> getInterface() {
		return interfacee;
	}
}
