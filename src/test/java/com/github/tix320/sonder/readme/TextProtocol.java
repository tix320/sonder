package com.github.tix320.sonder.readme;

import java.io.IOException;
import java.nio.charset.Charset;

import com.github.tix320.sonder.api.common.communication.*;
import com.github.tix320.sonder.api.common.event.EventListener;

public class TextProtocol implements Protocol {

	private TransferTunnel transferTunnel;

	@Override
	public void init(TransferTunnel transferTunnel, EventListener eventListener) {
		this.transferTunnel = transferTunnel; // hold transferTunnel for transfers sending in the future
	}

	public void sendText(String text, long clientId) {
		Charset charset = Charset.defaultCharset();

		byte[] bytes = text.getBytes(charset); // convert string to bytes by current charset

		Headers headers = Headers.builder().header(Headers.DESTINATION_ID, clientId) // specify destination client id
				.header("charset", charset.name()) // specify charset to help decoding on other side
				.build();

		Transfer transfer = new StaticTransfer(headers, bytes);

		transferTunnel.send(transfer);
	}

	@Override
	public void handleIncomingTransfer(Transfer transfer) throws IOException {
		Headers headers = transfer.getHeaders();
		long sourceId = headers.getNonNullLong(Headers.SOURCE_ID);
		String charset = headers.getNonNullString("charset"); // Get the charset for decoding content

		byte[] bytes = transfer.channel().readAll(); // read all content to byte array

		String text = new String(bytes, charset); // convert bytes to string according to charset

		System.out.printf("Message from %s was received: %s", sourceId, text);
	}

	@Override
	public void reset() {

	}

	@Override
	public String getName() {
		return "text-protocol";
	}
}
