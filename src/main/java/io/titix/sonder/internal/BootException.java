package io.titix.sonder.internal;

import java.util.Arrays;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * @author Tigran.Sargsyan on 18-Dec-18
 */
public final class BootException extends RuntimeException {

	BootException(String message) {
		super(message);
	}

	public BootException(Throwable cause) {
		super(cause);
	}

	static void checkAndThrow(String rootMessage, Check... checks) {
		var index = new Object() {
			int i = 0;
		};
		String checksMessage = Arrays.stream(checks)
				.filter(check -> check.isInvalid)
				.map(check -> ++index.i + ": " + check.message)
				.collect(Collectors.joining("\n"));
		if (checksMessage.isEmpty()) {
			return;
		}

		String message = (rootMessage + "\n" + checksMessage);

		throw new BootException(message);
	}

	public static Check check(Supplier<Boolean> predicate, String message) {
		return new Check(predicate.get(), message);
	}

	@SuppressWarnings("WeakerAccess")
	static final class Check {

		private final boolean isInvalid;

		private final String message;

		private Check(boolean isInvalid, String message) {
			this.isInvalid = isInvalid;
			this.message = message;
		}
	}
}
