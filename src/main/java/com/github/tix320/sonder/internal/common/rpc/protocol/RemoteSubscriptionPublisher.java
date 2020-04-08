package com.github.tix320.sonder.internal.common.rpc.protocol;


import java.util.TreeMap;

import com.github.tix320.kiwi.api.reactive.publisher.Publisher;
import com.github.tix320.kiwi.api.util.collection.Tuple;
import com.github.tix320.sonder.internal.common.rpc.service.OriginMethod;

/**
 * @author Tigran Sargsyan on 22-Mar-20.
 */
public final class RemoteSubscriptionPublisher {

	private final Publisher<Object> itemsPublisher;

	private final OriginMethod originMethod;

	private final TreeMap<Long, Tuple<Boolean, Object>> itemsBuffer;

	private long lastPublishedObjectId;

	public RemoteSubscriptionPublisher(Publisher<Object> itemsPublisher, OriginMethod originMethod) {
		this.itemsPublisher = itemsPublisher;
		this.originMethod = originMethod;
		this.itemsBuffer = new TreeMap<>();
		this.lastPublishedObjectId = 0;
	}

	public synchronized void publish(long objectId, boolean isNormal, Object item) {
		if (objectId == lastPublishedObjectId + 1) {
			publish(item, isNormal);
			lastPublishedObjectId = objectId;
			checkBuffer();
		}
		else {
			itemsBuffer.put(objectId, new Tuple<>(isNormal, item));
		}
	}

	public synchronized void forcePublishError(Throwable throwable) {
		itemsPublisher.publishError(throwable);
	}

	public synchronized void complete() {
		itemsPublisher.complete();
	}

	private void checkBuffer() {
		Long objectId;
		while (!itemsBuffer.isEmpty() && (objectId = itemsBuffer.firstKey()) == lastPublishedObjectId + 1) {
			Tuple<Boolean, Object> tuple = itemsBuffer.pollFirstEntry().getValue();
			publish(objectId, tuple.first(), tuple.second());
		}
	}

	private void publish(Object item, boolean isNormal) {
		if (isNormal) {
			itemsPublisher.publish(item);
		}
		else {
			itemsPublisher.publishError((Throwable) item);
		}
	}

	public OriginMethod getOriginMethod() {
		return originMethod;
	}
}
