package io.titix.sonder.internal;

import java.util.Objects;

/**
 * @author Tigran.Sargsyan on 08-Jan-19
 */
public class Param {

	public final int index;
	
	public final Class<?> type;

	public Param(int index, Class<?> type) {
		this.index = index;
		this.type = type;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		Param param = (Param) o;
		return index == param.index;
	}

	@Override
	public int hashCode() {
		return Objects.hash(index);
	}

	@Override
	public String toString() {
		return "Param{" + "index=" + index + '}';
	}
}
