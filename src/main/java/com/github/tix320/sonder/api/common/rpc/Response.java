package com.github.tix320.sonder.api.common.rpc;

/**
 * @author Tigran Sargsyan on 14-Jul-20.
 */
public final class Response<T> {

	private final Object value;

	private final boolean isSuccess;

	public Response(Object value, boolean isSuccess) {
		this.value = value;
		this.isSuccess = isSuccess;
	}

	@SuppressWarnings("unchecked")
	public T getResult() {
		if (isSuccess) {
			return (T) value;
		}
		else {
			throw new IllegalStateException("Not value");
		}
	}

	public Throwable getError() {
		if (isSuccess) {
			throw new IllegalStateException("Not error");
		}
		else {
			return (Throwable) value;
		}
	}

	public boolean isSuccess() {
		return isSuccess;
	}

	public boolean isError() {
		return !isSuccess;
	}

	@Override
	public String toString() {
		return "Response{" + "value=" + value + ", isSuccess=" + isSuccess + '}';
	}
}
