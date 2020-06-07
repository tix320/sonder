package com.github.tix320.sonder.internal.common;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum BuiltInProtocol {
	RPC("sonder-RPC"),
	TOPIC("sonder-topic");

	public static final Set<String> NAMES = Arrays.stream(BuiltInProtocol.values())
			.map(BuiltInProtocol::getName)
			.collect(Collectors.toSet());

	private final String name;

	BuiltInProtocol(String name) {
		this.name = name;
	}

	public String getName() {
		return name;
	}
}
