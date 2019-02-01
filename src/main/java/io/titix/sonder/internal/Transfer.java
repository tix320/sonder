package io.titix.sonder.internal;

import java.io.Serializable;

final class Transfer implements Serializable {

	private static final long serialVersionUID = -3285743284096636666L;

	final Headers headers;

	final Object content;

	Transfer(Headers headers, Object content) {
		this.headers = headers;
		this.content = content;
	}
}
