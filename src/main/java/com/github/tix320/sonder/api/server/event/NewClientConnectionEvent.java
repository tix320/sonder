package com.github.tix320.sonder.api.server.event;

import com.github.tix320.sonder.api.common.event.SonderEvent;

/**
 * @author Tigran Sargsyan on 22-Mar-20.
 */
public final class NewClientConnectionEvent implements SonderEvent {

	private final long clientId;

	public NewClientConnectionEvent(long clientId) {
		this.clientId = clientId;
	}

	public long getClientId() {
		return clientId;
	}
}
