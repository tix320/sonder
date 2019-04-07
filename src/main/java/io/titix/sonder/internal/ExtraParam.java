package io.titix.sonder.internal;

import java.util.Objects;

public class ExtraParam extends Param {

	public final String key;

	public ExtraParam(int index, String key) {
		super(index);
		this.key = key;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		if (!super.equals(o)) return false;
		ExtraParam that = (ExtraParam) o;
		return key.equals(that.key);
	}

	@Override
	public int hashCode() {
		return Objects.hash(super.hashCode(), key);
	}

	@Override
	public String toString() {
		return "ExtraParam{" + "key='" + key + '\'' + '}';
	}
}
