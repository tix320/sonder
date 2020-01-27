package com.github.tix320.sonder.internal.common.communication;

public class ProtocolException extends RuntimeException {

	public ProtocolException(String message) {
		super(message);
	}

	public ProtocolException(String message, Throwable cause) {
		super(message, cause);
	}
}
