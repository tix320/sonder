package com.github.tix320.sonder.api.server.event;

/**
 * @author Tigran Sargsyan on 22-Mar-20.
 */
public class ClientConnectionClosedEvent implements SonderServerEvent {

	private final long clientId;

	public ClientConnectionClosedEvent(long clientId) {
		this.clientId = clientId;
	}

	public long getClientId() {
		return clientId;
	}
}
