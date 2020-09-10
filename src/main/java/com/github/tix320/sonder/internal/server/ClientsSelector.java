package com.github.tix320.sonder.internal.server;

import java.io.Closeable;
import java.io.IOException;
import java.util.function.Consumer;

import com.github.tix320.sonder.internal.common.communication.Pack;

public interface ClientsSelector extends Closeable {

	void run(Consumer<ClientPack> packConsumer) throws IOException;

	void send(ClientPack clientPack) throws ClientClosedException;

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
