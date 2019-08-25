package io.titix.sonder.internal;

/**
 * @author tix32 on 13-Jan-19
 */
final class InternalException extends RuntimeException {

	private static final long serialVersionUID = -922010576856979068L;

	InternalException(Throwable cause) {
		super("If you see this exception, please contact us.", cause);
	}
}
