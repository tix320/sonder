package com.github.tix320.sonder.internal.common.rpc.service;

final class DuplicatePathException extends RuntimeException {
	private static final long serialVersionUID = 6360125090465073978L;

	DuplicatePathException(String message) {
		super(message);
	}
}
