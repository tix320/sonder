package com.github.tix320.sonder.internal.common.rpc.protocol;


import com.github.tix320.kiwi.publisher.MonoPublisher;
import com.github.tix320.sonder.internal.common.rpc.service.OriginMethod;

/**
 * @author Tigran Sargsyan on 22-Mar-20.
 */
public final class RequestMetadata {

	private final MonoPublisher<Object> responsePublisher;

	private final OriginMethod originMethod;

	public RequestMetadata(MonoPublisher<Object> responsePublisher, OriginMethod originMethod) {
		this.responsePublisher = responsePublisher;
		this.originMethod = originMethod;
	}

	public MonoPublisher<Object> getResponsePublisher() {
		return responsePublisher;
	}

	public OriginMethod getOriginMethod() {
		return originMethod;
	}
}
