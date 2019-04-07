package io.titix.sonder.internal;

/**
 * @author Tigran.Sargsyan on 21-Feb-19
 */
public final class SonderException extends RuntimeException {

	private static final long serialVersionUID = -7096534150861426353L;

	public SonderException(Throwable cause) {
		super(cause);
	}

	public SonderException(String message) {
		super(message);
	}

	public SonderException(String message, Throwable cause) {
		super(message, cause);
	}
}
