package com.github.tix320.sonder.api.client;

import java.io.IOException;
import java.util.function.LongFunction;

import com.github.tix320.sonder.api.common.communication.Protocol;
import com.github.tix320.sonder.api.common.communication.Transfer;
import com.github.tix320.sonder.api.common.event.EventListener;

/**
 * Protocol must implement method {@link #handleIncomingTransfer(Transfer)} for receiving data
 * and use {@link TransferTunnel} interface for sending data, which will be injected via {@link #init(TransferTunnel, EventListener)} method.
 */
public interface ClientSideProtocol extends Protocol {

	/**
	 * This method will be called once on server/client instance creation for some protocol initialization.
	 *
	 * @param transferTunnel for sending transfers.
	 * @param eventListener  for listening some events.
	 */
	void init(TransferTunnel transferTunnel, EventListener eventListener);

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
	 * @see SonderClientBuilder#contentTimeoutDurationFactory(LongFunction)
	 */
	void handleIncomingTransfer(Transfer transfer) throws IOException;
}
