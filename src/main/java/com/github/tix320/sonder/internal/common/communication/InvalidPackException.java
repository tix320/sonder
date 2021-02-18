package com.github.tix320.sonder.internal.common.communication;

import java.io.IOException;

public final class InvalidPackException extends IOException {

	public InvalidPackException(String message) {
		super(message);
	}
}
