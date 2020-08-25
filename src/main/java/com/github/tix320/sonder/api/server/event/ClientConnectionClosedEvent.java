package com.github.tix320.sonder.api.server.event;

import com.github.tix320.sonder.api.common.event.SonderEvent;

/**
 * @author Tigran Sargsyan on 22-Mar-20.
 */
public class ClientConnectionClosedEvent implements SonderEvent {

	private final long clientId;

	public ClientConnectionClosedEvent(long clientId) {
		this.clientId = clientId;
	}

	public long getClientId() {
		return clientId;
	}
}
