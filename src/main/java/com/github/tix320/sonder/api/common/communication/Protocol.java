package com.github.tix320.sonder.api.common.communication;

import java.io.Closeable;
import java.io.IOException;
import java.util.function.LongFunction;

import com.github.tix320.kiwi.api.observable.Observable;
import com.github.tix320.sonder.api.client.Clonder;
import com.github.tix320.sonder.api.client.ClonderBuilder;
import com.github.tix320.sonder.api.server.Sonder;
import com.github.tix320.sonder.api.server.SonderBuilder;

/**
 * Protocol is used for handling transfers sent between clients and server.
 * Each protocol must have unique name in one server or client scope. Method {@link #getName()} must return it.
 * Protocol id defined by implementing two methods for sending and receiving data. {@link #outgoingTransfers()} and {@link #handleIncomingTransfer(Transfer)} respectively.
 *
 * @see Sonder
 * @see Clonder
 */
public interface Protocol extends Closeable {

	/**
	 * This method will be called if any transfer received for this protocol.
	 * Implementation must necessarily fully read content from transfer, even if for some reason this is not necessary.
	 * Socket channel will be blocked for other transfers until content fully read.
	 * If content not be read a long time, a timeout may happen, according to the configuration.
	 *
	 * @param transfer to handle
	 *
	 * @throws IOException if any IO errors occurs
	 * @see SonderBuilder#contentTimeoutDurationFactory(LongFunction)
	 * @see ClonderBuilder#contentTimeoutDurationFactory(LongFunction)
	 */
	void handleIncomingTransfer(Transfer transfer)
			throws IOException;

	/**
	 * This method will be called only once, and then returned observable will be subscribed
	 * for receiving output transfers from this protocol.
	 *
	 * @return observable of outgoing transfers.
	 */
	Observable<Transfer> outgoingTransfers();

	/**
	 * Return name of protocol.
	 *
	 * @return name
	 */
	String getName();
}
