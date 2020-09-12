package com.github.tix320.sonder.internal.common.rpc.exception;

import com.github.tix320.sonder.internal.common.rpc.protocol.ContentType;
import com.github.tix320.sonder.internal.common.rpc.exception.RPCProtocolException;

public final class UnsupportedContentTypeException extends RPCProtocolException {

	public UnsupportedContentTypeException(ContentType contentType) {
		super(String.format("Unsupported content type %s", contentType));
	}
}
