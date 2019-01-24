package io.titix.sonder;

/**
 * @author Tigran.Sargsyan on 14-Dec-18
 */
public interface Callback<T> {

	void call(T result);
}
