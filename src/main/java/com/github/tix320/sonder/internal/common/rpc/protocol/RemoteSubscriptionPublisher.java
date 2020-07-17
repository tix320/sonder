package com.github.tix320.sonder.internal.common.rpc.protocol;


import java.util.TreeMap;

import com.github.tix320.kiwi.api.reactive.publisher.Publisher;
import com.github.tix320.sonder.internal.common.rpc.service.OriginMethod;

/**
 * @author Tigran Sargsyan on 22-Mar-20.
 */
public final class RemoteSubscriptionPublisher {

	private final Object completeObject = new Object();

	private final Publisher<Object> itemsPublisher;

	private final OriginMethod originMethod;

	private final TreeMap<Long, Object> itemsBuffer;

	private long lastOrderId;

	public RemoteSubscriptionPublisher(Publisher<Object> itemsPublisher, OriginMethod originMethod) {
		this.itemsPublisher = itemsPublisher;
		this.originMethod = originMethod;
		this.itemsBuffer = new TreeMap<>();
		this.lastOrderId = 0;
	}

	public synchronized void publish(long orderId, Object item) {
		if (orderId == lastOrderId + 1) {
			itemsPublisher.publish(item);
			lastOrderId = orderId;
			checkBuffer();
		}
		else {
			itemsBuffer.put(orderId, item);
		}
	}

	public synchronized void complete(long orderId) {
		if (orderId == lastOrderId + 1) {
			itemsPublisher.complete();
			lastOrderId = orderId;
		}
		else {
			itemsBuffer.put(orderId, completeObject);
		}
	}

	private void checkBuffer() {
		Long objectId;
		while (!itemsBuffer.isEmpty() && (objectId = itemsBuffer.firstKey()) == lastOrderId + 1) {
			Object item = itemsBuffer.pollFirstEntry().getValue();
			if (item == completeObject) {
				itemsPublisher.complete();
				break;
			}
			publish(objectId, item);
		}
	}

	public OriginMethod getOriginMethod() {
		return originMethod;
	}
}
