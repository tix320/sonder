package com.github.tix320.sonder.internal.common.rpc.exception;

public class BadRequestException extends RPCProtocolException {

	public BadRequestException(String message) {
		super(message);
	}

	public BadRequestException(Throwable cause) {
		super(cause);
	}

	public BadRequestException(String message, Throwable cause) {
		super(message, cause);
	}
}
