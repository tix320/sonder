package com.github.tix320.sonder.api.common.rpc;

/**
 * @author Tigran Sargsyan on 14-Jul-20.
 */
public final class Response<T> {

	private final Object value;

	private final boolean isSuccess;

	public Response(Object value) {
		this.value = value;
		this.isSuccess = true;
	}

	public Response(Exception value) {
		this.value = value;
		this.isSuccess = false;
	}

	/**
	 * Returns result if success, fails otherwise.
	 *
	 * @return response.
	 *
	 * @throws Exception if there are error.
	 */
	@SuppressWarnings("unchecked")
	public T get() throws Exception {
		if (isSuccess) {
			return (T) value;
		} else {
			throw ((Exception) value);
		}
	}

	@Override
	public String toString() {
		return "Response{" + "value=" + value + ", isSuccess=" + isSuccess + '}';
	}
}
