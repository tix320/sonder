package com.github.tix320.sonder.internal.common.communication;

import com.github.tix320.sonder.api.common.communication.ContentType;

public class UnsupportedContentTypeException extends RuntimeException {

	public UnsupportedContentTypeException(ContentType contentType) {
		super(String.format("Unsupported content type %s", contentType));
	}
}
