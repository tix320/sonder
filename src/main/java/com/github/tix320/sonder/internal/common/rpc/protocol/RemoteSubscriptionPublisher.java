package com.github.tix320.sonder.internal.common.rpc.protocol;


import java.util.Map.Entry;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JavaType;
import com.github.tix320.kiwi.api.reactive.publisher.Publisher;

/**
 * @author Tigran Sargsyan on 22-Mar-20.
 */
public final class RemoteSubscriptionPublisher {

	private static final Object COMPLETE_OBJECT = new Object();

	private final Publisher<Object> itemsPublisher;

	private final JavaType itemType;

	private final TreeMap<Long, Object> itemsBuffer;

	private long lastOrderId;

	public RemoteSubscriptionPublisher(Publisher<Object> itemsPublisher, JavaType itemType) {
		this.itemsPublisher = itemsPublisher;
		this.itemType = itemType;
		this.itemsBuffer = new TreeMap<>();
		this.lastOrderId = 0;
	}

	public void publish(long orderId, Object item) {
		synchronized (this) {
			boolean success = publishInternal(orderId, item);
			if (success) {
				publishFromBuffer();
			} else {
				itemsBuffer.put(orderId, item);
			}
		}
	}

	public synchronized void complete(long orderId) {
		synchronized (this) {
			if (orderId == lastOrderId + 1) {
				itemsPublisher.complete();
				lastOrderId = orderId;
			} else {
				itemsBuffer.put(orderId, COMPLETE_OBJECT);
			}
		}
	}

	public void closePublisher() {
		synchronized (this) {
			itemsPublisher.complete();
		}
	}

	public JavaType getItemType() {
		return itemType;
	}

	private void publishFromBuffer() {
		while (!itemsBuffer.isEmpty()) {
			Entry<Long, Object> firstEntry = itemsBuffer.pollFirstEntry();
			Long orderId = firstEntry.getKey();
			Object item = firstEntry.getValue();
			if (item == COMPLETE_OBJECT) {
				itemsPublisher.complete();
				break;
			} else {
				boolean success = publishInternal(orderId, item);
				if (!success) {
					break;
				}
			}
		}
	}

	private boolean publishInternal(long orderId, Object item) {
		if (orderId == lastOrderId + 1) {
			itemsPublisher.publish(item);
			lastOrderId = orderId;
			return true;
		} else {
			return false;
		}
	}
}
