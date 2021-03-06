package com.github.tix320.sonder.readme.example;

import java.io.IOException;

import com.github.tix320.sonder.api.common.communication.ChannelTransfer;
import com.github.tix320.sonder.api.common.communication.Headers;
import com.github.tix320.sonder.api.common.communication.Transfer;
import com.github.tix320.sonder.api.server.event.ServerEvents;
import com.github.tix320.sonder.api.server.ServerSideProtocol;
import com.github.tix320.sonder.api.server.TransferTunnel;

public class ServerTextProtocol implements ServerSideProtocol {

	private TransferTunnel transferTunnel;

	@Override
	public void init(TransferTunnel transferTunnel, ServerEvents serverEvents) {
		this.transferTunnel = transferTunnel; // hold transferTunnel for transfers sending in the future
	}

	@Override
	public void handleIncomingTransfer(long clientId, Transfer transfer) throws IOException {
		Headers headers = transfer.headers();
		long toClientId = headers.getNonNullLong("to-id");

		Headers newHeaders = headers.compose().header("from-id", clientId).build();

		transfer = new ChannelTransfer(newHeaders, transfer.contentChannel());
		transferTunnel.send(toClientId, transfer);
	}

	@Override
	public String getName() {
		return "text-protocol";
	}
}
