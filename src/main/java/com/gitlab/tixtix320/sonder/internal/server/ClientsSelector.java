package com.gitlab.tixtix320.sonder.internal.server;

import java.io.Closeable;

import com.gitlab.tixtix320.kiwi.api.observable.Observable;
import com.gitlab.tixtix320.sonder.internal.common.communication.Pack;

public interface ClientsSelector extends Closeable {

	Observable<ClientPack> incomingRequests();

	void send(ClientPack clientPack);

	class ClientPack {
		private final long clientId;

		private final Pack pack;

		public ClientPack(long clientId, Pack pack) {
			this.clientId = clientId;
			this.pack = pack;
		}

		public long getClientId() {
			return clientId;
		}

		public Pack getPack() {
			return pack;
		}
	}
}
