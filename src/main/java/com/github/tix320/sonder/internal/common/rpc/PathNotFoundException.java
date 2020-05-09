package com.github.tix320.sonder.internal.common.rpc;

public final class PathNotFoundException extends RPCProtocolException {
	private static final long serialVersionUID = -8210390793618496884L;

	public PathNotFoundException(String message) {
		super(message);
	}
}
