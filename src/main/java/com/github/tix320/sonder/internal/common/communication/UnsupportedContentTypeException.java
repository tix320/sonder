package com.github.tix320.sonder.internal.common.communication;

import com.github.tix320.sonder.api.common.communication.ContentType;
import com.github.tix320.sonder.internal.common.rpc.exception.RPCProtocolException;

public class UnsupportedContentTypeException extends RPCProtocolException {

	public UnsupportedContentTypeException(ContentType contentType) {
		super(String.format("Unsupported content type %s", contentType));
	}
}
