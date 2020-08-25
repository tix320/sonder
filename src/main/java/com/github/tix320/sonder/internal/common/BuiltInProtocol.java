package com.github.tix320.sonder.internal.common;

public enum BuiltInProtocol {
	RPC("sonder-RPC"),
	TOPIC("sonder-topic");

	private final String name;

	BuiltInProtocol(String name) {
		this.name = name;
	}

	public String getName() {
		return name;
	}
}
