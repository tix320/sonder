package com.github.tix320.sonder.internal.common.rpc;

public class RPCProtocolException extends RuntimeException {

	public RPCProtocolException(String message) {
		super(message);
	}

	public RPCProtocolException(String message, Throwable cause) {
		super(message, cause);
	}
}
