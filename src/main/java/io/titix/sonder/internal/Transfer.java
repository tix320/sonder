package io.titix.sonder.internal;

import java.io.Serializable;

public final class Transfer<T> implements Serializable {

	private static final long serialVersionUID = -3285743284096636666L;

	public final Headers headers;

	public final T content;

	public Transfer(Headers headers, T content) {
		this.headers = headers;
		this.content = content;
	}
}
