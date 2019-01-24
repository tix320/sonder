package io.titix.sonder.internal;

import java.io.Serializable;

final class Transfer implements Serializable {

	final Headers headers;

	final Object content;

	Transfer(Headers headers, Object content) {
		this.headers = headers;
		this.content = content;
	}
}
