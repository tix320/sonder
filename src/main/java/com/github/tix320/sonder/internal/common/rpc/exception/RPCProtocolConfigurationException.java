package com.github.tix320.sonder.internal.common.rpc.exception;

import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * @author Tigran.Sargsyan on 18-Dec-18
 */
public final class RPCProtocolConfigurationException extends RuntimeException {
	private static final long serialVersionUID = -4222792746745482561L;

	public RPCProtocolConfigurationException(String message) {
		super(message);
	}

	@SafeVarargs
	public static <T> void checkAndThrow(T value, Function<T, String> rootMessage, Check<T>... checks) {
		String checksMessage = IntStream.range(0, checks.length)
				.filter(index -> checks[index].predicate.test(value))
				.mapToObj(index -> index + ": " + checks[index].errorMessage)
				.collect(Collectors.joining("\n"));
		if (checksMessage.isEmpty()) {
			return;
		}

		String message = (rootMessage.apply(value) + '\n' + checksMessage);

		throw new RPCProtocolConfigurationException(message);
	}

	public static <T> Check<T> throwWhen(Predicate<T> predicate, String errorMessage) {
		return new Check<>(predicate, errorMessage);
	}

	public static final class Check<T> {

		private final Predicate<T> predicate;

		private final String errorMessage;

		private Check(Predicate<T> predicate, String errorMessage) {
			this.predicate = predicate;
			this.errorMessage = errorMessage;
		}
	}
}
