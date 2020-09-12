package com.github.tix320.sonder.api.common.communication;

import java.io.IOException;
import java.util.function.LongFunction;

import com.github.tix320.sonder.api.client.SonderClient;
import com.github.tix320.sonder.api.client.SonderClientBuilder;
import com.github.tix320.sonder.api.common.event.EventListener;
import com.github.tix320.sonder.api.server.SonderServer;
import com.github.tix320.sonder.api.server.SonderServerBuilder;

/**
 * Protocol is used for handling transfers sent between clients and server.
 * Each protocol must have unique name in one server or client scope. Method {@link #getName()} must return it.
 * Protocol must implement method {@link #handleIncomingTransfer(Transfer)} for receiving data
 * and use {@link TransferTunnel} interface for sending data, which will be injected via {@link Protocol#init(TransferTunnel, EventListener)} method.
 * NOTE: Implementation must be thread-safe.
 *
 * @see SonderServer
 * @see SonderClient
 */
public interface Protocol {

	/**
	 * This method will be called once on server/client instance creation for some protocol initialization.
	 *
	 * @param transferTunnel for sending transfers.
	 * @param eventListener  for listening some events.
	 */
	void init(TransferTunnel transferTunnel, EventListener eventListener);

	/**
	 * This method will be called if any transfer received for this protocol.
	 * Implementation must necessarily fully read content from transfer, even if for some reason this is not necessary.
	 * Socket channel will be blocked for other transfers until content fully read.
	 * If content not be read a long time, a timeout may happen, according to the configuration.
	 *
	 * @param transfer to handle
	 *
	 * @throws IOException if any IO errors occurs
	 * @see SonderServerBuilder#contentTimeoutDurationFactory(LongFunction)
	 * @see SonderClientBuilder#contentTimeoutDurationFactory(LongFunction)
	 */
	void handleIncomingTransfer(Transfer transfer) throws IOException;

	/**
	 * This method will be called on server/client close or connection lost for some cleanup.
	 * You must reset protocol to state, so that can work properly after reconnect.
	 */
	void reset();

	/**
	 * Return name of protocol.
	 *
	 * @return name
	 */
	String getName();
}
