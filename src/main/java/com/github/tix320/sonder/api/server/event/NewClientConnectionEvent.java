package com.github.tix320.sonder.api.server.event;

/**
 * @author Tigran Sargsyan on 22-Mar-20.
 */
public class NewClientConnectionEvent implements SonderServerEvent {

	private final long clientId;

	public NewClientConnectionEvent(long clientId) {
		this.clientId = clientId;
	}

	public long getClientId() {
		return clientId;
	}
}
