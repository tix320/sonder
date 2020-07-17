package com.github.tix320.sonder.internal.common.rpc.protocol;

/**
 * @author Tigran Sargsyan on 14-Jul-20.
 */
public class UnhandledErrorResponseException extends RuntimeException {

	public UnhandledErrorResponseException(Throwable cause) {
		super("See cause", cause);
	}
}
