package com.github.tix320.sonder.api.common.communication;

import java.io.IOException;
import java.util.function.LongFunction;

import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.sonder.api.client.SonderClient;
import com.github.tix320.sonder.api.client.SonderClientBuilder;
import com.github.tix320.sonder.api.common.event.SonderEventDispatcher;
import com.github.tix320.sonder.api.server.SonderServer;
import com.github.tix320.sonder.api.server.SonderServerBuilder;

/**
 * Protocol is used for handling transfers sent between clients and server.
 * Each protocol must have unique name in one server or client scope. Method {@link #getName()} must return it.
 * Protocol id defined by implementing two methods for sending and receiving data. {@link #outgoingTransfers()} and {@link #handleIncomingTransfer(Transfer)} respectively.
 *
 * @see SonderServer
 * @see SonderClient
 */
public interface Protocol {

	/**
	 * This method will be called once on server/client start for some protocol initialization.
	 *
	 * @param sonderEventDispatcher for emitting and listening some events.
	 */
	void init(SonderEventDispatcher sonderEventDispatcher);

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
	 * This method will be called only once, and then returned observable will be subscribed
	 * for receiving output transfers from this protocol.
	 *
	 * @return observable of outgoing transfers.
	 */
	Observable<Transfer> outgoingTransfers();

	/**
	 * This method will be called once on server/client close for some protocol destruction.
	 */
	void destroy();

	/**
	 * Return name of protocol.
	 *
	 * @return name
	 */
	String getName();
}
