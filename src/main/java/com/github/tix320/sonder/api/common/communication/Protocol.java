package com.github.tix320.sonder.api.common.communication;

import com.github.tix320.sonder.api.client.SonderClient;
import com.github.tix320.sonder.api.server.SonderServer;

/**
 * Protocol is used for handling transfers sent between clients and server.
 * Each protocol must have unique name in one server or client scope. Method {@link #getName()} must return it.
 * NOTE: Implementation must be thread-safe.
 *
 * @see SonderServer
 * @see SonderClient
 */
public interface Protocol {

	/**
	 * Return name of protocol.
	 *
	 * @return name
	 */
	String getName();
}
