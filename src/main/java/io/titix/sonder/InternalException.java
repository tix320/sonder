package io.titix.sonder;

/**
 * @author tix32 on 13-Jan-19
 */
public final class InternalException extends RuntimeException {

	public InternalException(String message) {
		super(message);
	}

	public InternalException(String message, Throwable cause) {
		super(message, cause);
	}
}
