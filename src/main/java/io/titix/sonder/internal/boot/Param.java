package io.titix.sonder.internal.boot;

/**
 * @author Tigran.Sargsyan on 08-Jan-19
 */
public final class Param {

	final String key;

	final boolean isExtra;

	Param(String key, boolean isExtra) {
		this.key = key;
		this.isExtra = isExtra;
	}

	@Override
	public String toString() {
		return "Param{" + "key='" + key + '\'' + ", isExtra=" + isExtra + '}';
	}
}
