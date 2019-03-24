package io.titix.sonder.internal.boot;

import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * @author Tigran.Sargsyan on 18-Dec-18
 */
public final class BootException extends RuntimeException {

	public BootException(String message) {
		super(message);
	}

	public BootException(Throwable cause) {
		super(cause);
	}

	static void checkAndThrow(String rootMessage, Check... checks) {
		String checksMessage = IntStream.range(0, checks.length)
				.filter(index -> checks[index].isInvalid)
				.mapToObj(index -> index + ": " + checks[index].message)
				.collect(Collectors.joining("\n"));
		if (checksMessage.isEmpty()) {
			return;
		}

		String message = (rootMessage + "\n" + checksMessage);

		throw new BootException(message);
	}

	public static Check check(BooleanSupplier predicate, String message) {
		return new Check(predicate.getAsBoolean(), message);
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
