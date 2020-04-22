package com.github.tix320.sonder.internal.common.rpc.protocol;


import java.util.TreeMap;

import com.github.tix320.kiwi.api.reactive.publisher.Publisher;
import com.github.tix320.kiwi.api.util.collection.Tuple;
import com.github.tix320.sonder.internal.common.rpc.service.OriginMethod;

/**
 * @author Tigran Sargsyan on 22-Mar-20.
 */
public final class RemoteSubscriptionPublisher {

	private final Object completeObject = new Object();

	private final Publisher<Object> itemsPublisher;

	private final OriginMethod originMethod;

	private final TreeMap<Long, Tuple<Boolean, Object>> itemsBuffer;

	private long lastOrderId;

	public RemoteSubscriptionPublisher(Publisher<Object> itemsPublisher, OriginMethod originMethod) {
		this.itemsPublisher = itemsPublisher;
		this.originMethod = originMethod;
		this.itemsBuffer = new TreeMap<>();
		this.lastOrderId = 0;
	}

	public synchronized void publish(long orderId, boolean isNormal, Object item) {
		if (orderId == lastOrderId + 1) {
			publish(item, isNormal);
			lastOrderId = orderId;
			checkBuffer();
		}
		else {
			itemsBuffer.put(orderId, new Tuple<>(isNormal, item));
		}
	}

	public synchronized void forcePublishError(Throwable throwable) {
		itemsPublisher.publishError(throwable);
	}

	public synchronized void complete(long orderId) {
		if (orderId == lastOrderId + 1) {
			itemsPublisher.complete();
			lastOrderId = orderId;
		}
		else {
			itemsBuffer.put(orderId, new Tuple<>(true, completeObject));
		}
	}

	private void checkBuffer() {
		Long objectId;
		while (!itemsBuffer.isEmpty() && (objectId = itemsBuffer.firstKey()) == lastOrderId + 1) {
			Tuple<Boolean, Object> tuple = itemsBuffer.pollFirstEntry().getValue();
			Object object = tuple.second();
			if (object == completeObject) {
				itemsPublisher.complete();
				break;
			}
			publish(objectId, tuple.first(), object);
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
