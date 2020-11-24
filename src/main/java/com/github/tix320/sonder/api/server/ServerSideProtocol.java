package com.github.tix320.sonder.api.server;

import com.github.tix320.sonder.api.common.communication.Protocol;
import com.github.tix320.sonder.api.common.communication.Transfer;
import com.github.tix320.sonder.api.common.event.EventListener;

import java.io.IOException;
import java.util.function.LongFunction;

/**
 * Protocol must implement method {@link #handleIncomingTransfer(long, Transfer)} for receiving data
 * and use {@link TransferTunnel} interface for sending data, which will be injected via {@link #init(TransferTunnel, EventListener)} method.
 */
public interface ServerSideProtocol extends Protocol {

	/**
	 * This method will be called once on server/client instance creation for some protocol initialization.
	 *
	 * @param transferTunnel for sending transfers.
	 * @param eventListener  for listening some events.
	 */
	void init(TransferTunnel transferTunnel, EventListener eventListener);

	/**
	 * This method will be called if any transfer received for this protocol.
	 * NOTE: Implementation must read content channel in synchronous way,
	 * because that channel is just wrapper over socket channel (to avoid unnecessary copy),
	 * so data will be dropped after this method call.
	 *
	 * @param transfer to handle
	 *
	 * @throws IOException if any IO errors occurs
	 * @see SonderServerBuilder#contentTimeoutDurationFactory(LongFunction)
	 */
	void handleIncomingTransfer(long clientId, Transfer transfer) throws IOException;
}
