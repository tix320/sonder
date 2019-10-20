package com.gitlab.tixtix320.sonder.internal.common.rpc.service;

import java.util.Objects;

import com.fasterxml.jackson.databind.JavaType;

/**
 * @author Tigran.Sargsyan on 08-Jan-19
 */
public class Param {

	protected final int index;

	protected final JavaType type;

	public Param(int index, JavaType type) {
		this.index = index;
		this.type = type;
	}

	public int getIndex() {
		return index;
	}

	public JavaType getType() {
		return type;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		Param param = (Param) o;
		return index == param.index && type.equals(param.type);
	}

	@Override
	public int hashCode() {
		return Objects.hash(index, type);
	}

	@Override
	public String toString() {
		return "Param{" + "index=" + index + ", type=" + type + '}';
	}
}
