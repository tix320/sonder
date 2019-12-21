package com.gitlab.tixtix320.sonder.internal.server;

import java.io.Closeable;

import com.gitlab.tixtix320.kiwi.api.observable.Observable;

public interface ClientsSelector extends Closeable {

	Observable<ClientPack> incomingRequests();

	void send(ClientPack clientPack);

	class ClientPack {
		private final long clientId;

		private final byte[] data;

		public ClientPack(long clientId, byte[] data) {
			this.clientId = clientId;
			this.data = data;
		}

		public long getClientId() {
			return clientId;
		}

		public byte[] getData() {
			return data;
		}
	}
}
