package com.github.tix320.sonder.internal.common.exception;

/**
 * @author : Tigran Sargsyan
 * @since : 22.02.2021
 **/
public class ProtocolNotFoundException extends RuntimeException {

	public ProtocolNotFoundException(String protocolName) {
		super(String.format("Protocol %s not found", protocolName));
	}
}
