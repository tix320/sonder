package com.github.tix320.sonder.api.common.rpc;

public final class RPCRemoteException extends Exception {

	public RPCRemoteException(Throwable cause) {
		super("See cause", cause);
	}

	public RPCRemoteException(String errors) {
		super(errors);
		setStackTrace(new StackTraceElement[0]);
	}
}
