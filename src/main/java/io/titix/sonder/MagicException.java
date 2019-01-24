package io.titix.sonder;

/**
 * @author tix32 on 13-Jan-19
 */
public final class MagicException extends RuntimeException {

	public MagicException(String message) {
		super(message);
	}

	public MagicException(String message, Throwable cause) {
		super(message, cause);
	}
}
