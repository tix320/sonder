package com.github.tix320.sonder.readme.example;

import java.io.IOException;
import java.nio.charset.Charset;

import com.github.tix320.sonder.api.client.ClientSideProtocol;
import com.github.tix320.sonder.api.client.TransferTunnel;
import com.github.tix320.sonder.api.client.event.ClientEvents;
import com.github.tix320.sonder.api.common.communication.Headers;
import com.github.tix320.sonder.api.common.communication.StaticTransfer;
import com.github.tix320.sonder.api.common.communication.Transfer;

public class ClientTextProtocol implements ClientSideProtocol {

	private TransferTunnel transferTunnel;

	@Override
	public void init(TransferTunnel transferTunnel, ClientEvents clientEvents) {
		this.transferTunnel = transferTunnel; // hold transferTunnel for transfers sending in the future
	}

	@Override
	public void reset() {
		this.transferTunnel = null;
	}

	@Override
	public void handleIncomingTransfer(Transfer transfer) throws IOException {
		Headers headers = transfer.headers();
		String charset = headers.getNonNullString("charset"); // Get the charset for decoding content
		long fromClientId = headers.getNonNullLong("from-id");

		byte[] bytes = transfer.contentChannel().readAllBytes(); // read all content to byte array

		String text = new String(bytes, charset); // convert bytes to string according to charset

		System.out.printf("Message from %s was received: %s", fromClientId, text);
	}

	public void sendText(String text, long clientId) {
		Charset charset = Charset.defaultCharset();

		byte[] bytes = text.getBytes(charset); // convert string to bytes by current charset

		Headers headers = Headers.builder()
				.header("to-id", clientId)
				.header("charset", charset.name()) // specify charset to help decoding on other side
				.build();

		Transfer transfer = new StaticTransfer(headers, bytes);

		transferTunnel.send(transfer);
	}

	@Override
	public String getName() {
		return "text-protocol";
	}
}
