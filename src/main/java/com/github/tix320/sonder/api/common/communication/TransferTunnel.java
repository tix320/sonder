package com.github.tix320.sonder.api.common.communication;

/**
 * This interface is used in protocols {@link Protocol} for sending transfers from its.
 *
 * @author Tigran Sargsyan on 29-Aug-20
 */
public interface TransferTunnel {

	/**
	 * Send transfer.
	 *
	 * @param transfer to send.
	 */
	void send(Transfer transfer);
}
