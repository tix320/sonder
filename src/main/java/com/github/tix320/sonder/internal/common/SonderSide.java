package com.github.tix320.sonder.internal.common;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tix320.sonder.api.common.communication.ChannelTransfer;
import com.github.tix320.sonder.api.common.communication.Headers;
import com.github.tix320.sonder.api.common.communication.Protocol;
import com.github.tix320.sonder.api.common.communication.Transfer;
import com.github.tix320.sonder.internal.common.communication.Pack;
import com.github.tix320.sonder.internal.common.exception.HeadersConvertException;
import com.github.tix320.sonder.internal.common.exception.ProtocolNotFoundException;

/**
 * @author : Tigran Sargsyan
 * @since : 22.02.2021
 **/
public abstract class SonderSide<P extends Protocol> {

	private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

	private final Map<String, P> protocols;

	protected final AtomicReference<SonderSideState> state = new AtomicReference<>(SonderSideState.INITIAL);

	protected SonderSide(Map<String, P> protocols) {
		this.protocols = Map.copyOf(protocols);
	}

	protected final P findProtocol(Headers headers) {
		String protocolName = headers.getNonNullString(Headers.PROTOCOL);
		P protocol = protocols.get(protocolName);
		if (protocol == null) {
			throw new ProtocolNotFoundException(protocolName);
		}

		return protocol;
	}

	protected final Stream<P> protocols() {
		return protocols.values().stream();
	}

	protected static Transfer convertPackToTransfer(Pack pack) {
		Headers headers = deserializeHeaders(pack.getHeaders());
		return new ChannelTransfer(headers, pack.contentChannel());
	}

	protected static Pack convertTransferToPack(Transfer transfer) {
		byte[] headers = serializeHeaders(transfer.headers());

		return new Pack(headers, transfer.contentChannel());
	}

	private static byte[] serializeHeaders(Headers headers) {
		try {
			return JSON_MAPPER.writeValueAsBytes(headers);
		} catch (JsonProcessingException e) {
			throw new HeadersConvertException("Cannot convert bytes to JSON", e);
		}
	}

	private static Headers deserializeHeaders(byte[] headers) {
		try {
			return JSON_MAPPER.readValue(headers, Headers.class);
		} catch (IOException e) {
			throw new HeadersConvertException("Cannot parse JSON from bytes", e);
		}
	}

	protected static Transfer setProtocolHeader(Transfer transfer, String protocolName) {
		return new ChannelTransfer(transfer.headers().compose().header(Headers.PROTOCOL, protocolName).build(),
				transfer.contentChannel());
	}
}
