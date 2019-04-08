package io.titix.sonder.internal;

final class TransmitterException extends RuntimeException {
	private static final long serialVersionUID = -1357912081854809716L;

	TransmitterException(String message, Throwable cause) {
		super(message, cause);
	}
}
