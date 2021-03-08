package com.github.tix320.sonder.api.server;

import java.io.IOException;

import com.github.tix320.sonder.api.common.communication.Protocol;
import com.github.tix320.sonder.api.common.communication.Transfer;
import com.github.tix320.sonder.api.server.event.Events;

/**
 * Protocol must implement method {@link #handleIncomingTransfer(long, Transfer)} for receiving data
 * and use {@link TransferTunnel} interface for sending data, which will be injected via {@link #init(TransferTunnel, Events)} method.
 */
public interface ServerSideProtocol extends Protocol {

	/**
	 * This method will be called once on server instance creation for some protocol initialization.
	 *
	 * @param transferTunnel for sending transfers.
	 * @param events         for listening some events.
	 */
	void init(TransferTunnel transferTunnel, Events events);

	/**
	 * This method will be called if any transfer received for this protocol.
	 * NOTE: Implementation must read content channel anyway or close it,
	 * because that channel is just wrapper over socket channel (to avoid unnecessary copy),
	 * so future transfers will be blocked until current is processing.
	 *
	 * @param clientId from clientId
	 * @param transfer to handle
	 *
	 * @throws IOException if any IO errors occurs
	 */
	void handleIncomingTransfer(long clientId, Transfer transfer) throws IOException;
}
