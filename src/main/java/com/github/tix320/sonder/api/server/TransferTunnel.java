package com.github.tix320.sonder.api.server;

import com.github.tix320.sonder.api.common.communication.Protocol;
import com.github.tix320.sonder.api.common.communication.Transfer;

/**
 * This interface is used in protocols {@link Protocol} for sending transfers from its.
 *
 * @author Tigran Sargsyan on 29-Aug-20
 */
public interface TransferTunnel {

	/**
	 * Send transfer.
	 *
	 * @param clientId destination client id.
	 * @param transfer to send.
	 */
	void send(long clientId, Transfer transfer);
}
