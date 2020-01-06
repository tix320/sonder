package com.gitlab.tixtix320.sonder.api.common.communication;

import java.io.Serializable;

public final class Transfer implements Serializable {

	private static final long serialVersionUID = -3285743284096636666L;

	private final Headers headers;

	private final byte[] content;

	public Transfer(Headers headers, byte[] content) {
		this.headers = headers;
		this.content = content;
	}

	public Headers getHeaders() {
		return headers;
	}

	public byte[] getContent() {
		return content;
	}
}
