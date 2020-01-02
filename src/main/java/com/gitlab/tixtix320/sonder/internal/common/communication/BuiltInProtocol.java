package com.gitlab.tixtix320.sonder.internal.common.communication;

import java.util.List;

public enum BuiltInProtocol {
	RPC("sonder-RPC"),
	TOPIC("sonder-topic");

	public static final List<String> NAMES = List.of(RPC.name, TOPIC.name);

	private final String name;

	BuiltInProtocol(String name) {
		this.name = name;
	}

	public String getName() {
		return name;
	}
}
