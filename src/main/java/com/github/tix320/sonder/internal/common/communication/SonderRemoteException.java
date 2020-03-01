package com.github.tix320.sonder.internal.common.communication;

public class SonderRemoteException extends RuntimeException {

	private static final String ERROR_PREFIX = "An error occurred in remote, see stacktrace bellow:\n";

	public SonderRemoteException(String causeStackTrace) {
		super(ERROR_PREFIX + causeStackTrace);
		setStackTrace(new StackTraceElement[0]);
	}
}
