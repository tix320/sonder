package com.gitlab.tixtix320.sonder.internal.common.communication;

public final class Pack {

	private final byte[] headers;

	private final byte[] content;

	public Pack(byte[] headers, byte[] content) {
		this.headers = headers;
		this.content = content;
	}

	public byte[] getHeaders() {
		return headers;
	}

	public byte[] getContent() {
		return content;
	}

}
