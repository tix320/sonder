package com.github.tix320.sonder.internal.common.rpc;

public class RPCRemoteException extends RuntimeException {

	public RPCRemoteException(Throwable cause) {
		super("See cause", cause);
	}

	public RPCRemoteException(String errors) {
		super(errors);
		setStackTrace(new StackTraceElement[0]);
	}
}
