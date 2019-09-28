package com.gitlab.tixtix320.sonder.internal.common;

final class DuplicatePathException extends RuntimeException {
	private static final long serialVersionUID = 6360125090465073978L;

	DuplicatePathException(String message) {
		super(message);
	}
}
