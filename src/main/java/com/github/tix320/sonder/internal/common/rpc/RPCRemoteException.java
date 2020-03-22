package com.github.tix320.sonder.internal.common.rpc;

public class RPCRemoteException extends RuntimeException {

	private static final String ERROR_MESSAGE = "An error received from remote method";

	private static final String ERROR_PREFIX = "An error received from remote method, see stacktrace bellow:\n";

	public RPCRemoteException(Throwable cause) {
		super(ERROR_MESSAGE, cause);
	}

	public RPCRemoteException(String causeStackTrace) {
		super(ERROR_PREFIX + causeStackTrace);
		setStackTrace(new StackTraceElement[0]);
	}
}
