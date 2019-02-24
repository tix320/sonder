package io.titix.sonder;

import java.net.Socket;

import io.titix.kiwi.check.Try;
import io.titix.sonder.internal.Communicator;
import io.titix.sonder.internal.Config;
import io.titix.sonder.internal.SonderException;
import io.titix.sonder.internal.boot.EndpointBoot;
import io.titix.sonder.internal.boot.OriginBoot;

/**
 * @author tix32 on 20-Dec-18
 */
public final class Client {

	private static final OriginBoot originBoot = new OriginBoot(Config.getClientBootPackages());

	private static final EndpointBoot endpointBoot = new EndpointBoot(Config.getClientBootPackages());

	private final Communicator communicator;

	public Client(String host, int port) {
		communicator = new Communicator(createSocket(host, port), originBoot, endpointBoot);
	}

	public <T> T getService(Class<T> clazz) {
		T proxy = communicator.getService(clazz);
		if (proxy == null) {
			throw new IllegalArgumentException("Service of " + clazz + " not found");
		}
		return proxy;
	}

	public void stop() {
		if (communicator != null) {
			communicator.close();
		}
	}

	private Socket createSocket(String host, int port) {
		return Try.supply(() -> new Socket(host, port))
				.getOrElseThrow(throwable -> new SonderException("Cannot connect to server " + host + ":" + port));
	}
}
