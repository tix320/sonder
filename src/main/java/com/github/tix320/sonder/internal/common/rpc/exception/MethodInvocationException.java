package com.github.tix320.sonder.internal.common.rpc.exception;

public class MethodInvocationException extends RPCProtocolException{

	public MethodInvocationException(String message) {
		super(message);
	}

	public MethodInvocationException(Throwable cause) {
		super(cause);
	}

	public MethodInvocationException(String message, Throwable cause) {
		super(message, cause);
	}
}
