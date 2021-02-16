package com.github.tix320.sonder.api.client;

import java.io.IOException;

import com.github.tix320.sonder.api.client.event.ClientEvents;
import com.github.tix320.sonder.api.common.communication.Protocol;
import com.github.tix320.sonder.api.common.communication.Transfer;

/**
 * Protocol must implement method {@link #handleIncomingTransfer(Transfer)} for receiving data
 * and use {@link TransferTunnel} interface for sending data, which will be injected via {@link #init(TransferTunnel, ClientEvents)} method.
 */
public interface ClientSideProtocol extends Protocol {

	/**
	 * This method will be called once on server/client instance creation for some protocol initialization.
	 *
	 * @param transferTunnel for sending transfers.
	 * @param clientEvents   for listening some events.
	 */
	void init(TransferTunnel transferTunnel, ClientEvents clientEvents);

	/**
	 * This method will be called  for some cleanup on client close or connection lost.
	 * You must reset protocol to state, so that can work properly after reconnect.
	 */
	void reset();

	/**
	 * This method will be called if any transfer received for this protocol.
	 * NOTE: Implementation must read content channel in synchronous way,
	 * because that channel is just wrapper over socket channel (to avoid unnecessary copy),
	 * so data will be dropped after this method call.
	 *
	 * @param transfer to handle
	 *
	 * @throws IOException if any IO errors occurs
	 */
	void handleIncomingTransfer(Transfer transfer) throws IOException;
}
