package io.titix.sonder.internal;

import java.io.Serializable;

final class Headers implements Serializable {

	private static final long serialVersionUID = -1328960499870271557L;

	private final long id;

	private final String path;

	private final boolean isResponse;

	private final boolean needResponse;

	Headers(long id, String path, boolean isResponse, boolean needResponse) {
		this.id = id;
		this.path = path;
		this.isResponse = isResponse;
		this.needResponse = needResponse;
	}

	long getId() {
		return id;
	}

	String getPath() {
		return path;
	}

	boolean isResponse() {
		return isResponse;
	}

	boolean needResponse() {
		return needResponse;
	}
}
