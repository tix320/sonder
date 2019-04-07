package io.titix.sonder.internal;

import java.util.Arrays;
import java.util.Objects;

public final class OriginCall {

	public final OriginMethod signature;

	public final Object[] simpleArgs;

	public final ExtraArg[] extraArgs;

	public OriginCall(OriginMethod signature, Object[] simpleArgs, ExtraArg[] extraArgs) {
		this.signature = signature;
		this.simpleArgs = simpleArgs;
		this.extraArgs = extraArgs;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		OriginCall that = (OriginCall) o;
		return signature.equals(that.signature) && Arrays.equals(simpleArgs, that.simpleArgs);
	}

	@Override
	public int hashCode() {
		int result = Objects.hash(signature);
		result = 31 * result + Arrays.hashCode(simpleArgs);
		return result;
	}

	@Override
	public String toString() {
		return "OriginCall{" + "signature=" + signature + ", arguments=" + Arrays.toString(simpleArgs) + '}';
	}
}
