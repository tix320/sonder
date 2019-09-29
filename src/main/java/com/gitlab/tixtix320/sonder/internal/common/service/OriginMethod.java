package com.gitlab.tixtix320.sonder.internal.common.service;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;

import com.gitlab.tixtix320.sonder.internal.common.extra.ExtraParam;

/**
 * @author tix32 on 24-Feb-19
 */
public final class OriginMethod extends ServiceMethod {

	private final boolean needResponse;

	private final Destination destination;

	public OriginMethod(String path, Method method, List<Param> simpleParams, List<ExtraParam> extraParams,
						boolean needResponse, Destination destination) {
		super(path, method, simpleParams, extraParams);
		this.needResponse = needResponse;
		this.destination = destination;
	}

	public boolean needResponse() {
		return needResponse;
	}

	public Destination getDestination() {
		return destination;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		if (!super.equals(o)) return false;
		OriginMethod that = (OriginMethod) o;
		return needResponse == that.needResponse && destination == that.destination;
	}

	@Override
	public int hashCode() {
		return Objects.hash(super.hashCode(), needResponse, destination);
	}

	@Override
	public String toString() {
		return "OriginMethod{" + "needResponse=" + needResponse + ", destination=" + destination + ", path='" + path + '\'' + ", rawMethod=" + rawMethod + ", simpleParams=" + simpleParams + ", extraParams=" + extraParams + '}';
	}

	public enum Destination {
		CLIENT, SERVER
	}
}