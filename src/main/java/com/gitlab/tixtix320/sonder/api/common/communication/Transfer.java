package com.gitlab.tixtix320.sonder.api.common.communication;

import java.io.Serializable;

import com.fasterxml.jackson.databind.JsonNode;

public final class Transfer implements Serializable {

	private static final long serialVersionUID = -3285743284096636666L;

	private final Headers headers;

	private final JsonNode content;

	public Transfer(Headers headers, JsonNode content) {
		this.headers = headers;
		this.content = content;
	}

	public Headers getHeaders() {
		return headers;
	}

	public JsonNode getContent() {
		return content;
	}
}
