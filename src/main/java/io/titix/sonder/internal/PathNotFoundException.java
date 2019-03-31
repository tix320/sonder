package io.titix.sonder.internal;

final class PathNotFoundException extends RuntimeException {

	PathNotFoundException(String message) {
		super(message);
	}
}
