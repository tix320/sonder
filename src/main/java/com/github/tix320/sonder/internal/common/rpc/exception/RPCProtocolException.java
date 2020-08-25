package com.github.tix320.sonder.internal.common.rpc.exception;

public class RPCProtocolException extends RuntimeException {

	public RPCProtocolException(String message) {
		super(message);
	}

	public RPCProtocolException(Throwable cause) {
		super(cause);
	}

	public RPCProtocolException(String message, Throwable cause) {
		super(message, cause);
	}
}
