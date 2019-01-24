package io.titix.sonder.internal;

/**
 * @author Tigran.Sargsyan on 08-Jan-19
 */
final class Param {

	final String key;

	final boolean isExtra;

	Param(String key, boolean isExtra) {
		this.key = key;
		this.isExtra = isExtra;
	}

	@Override
	public String toString() {
		return "Param{" +
				"key='" + key + '\'' +
				", isExtra=" + isExtra +
				'}';
	}
}
