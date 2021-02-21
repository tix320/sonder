package com.github.tix320.sonder.api.common;

import java.net.InetSocketAddress;

/**
 * @author : Tigran Sargsyan
 * @since : 14.02.2021
 **/
public final class Client {

	private final long id;

	private final InetSocketAddress address;

	public Client(long id, InetSocketAddress address) {
		this.id = id;
		this.address = address;
	}

	public long getId() {
		return id;
	}

	public InetSocketAddress getAddress() {
		return address;
	}

	@Override
	public String toString() {
		return "Client{" + "id=" + id + ", address=" + address + '}';
	}
}
