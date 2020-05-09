package com.github.tix320.sonder.internal.server;

import java.io.Closeable;
import java.io.IOException;

import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.sonder.internal.common.communication.Pack;

public interface ClientsSelector extends Closeable {

	Observable<ClientPack> incomingRequests();

	void send(ClientPack clientPack);

	void run() throws IOException;

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
