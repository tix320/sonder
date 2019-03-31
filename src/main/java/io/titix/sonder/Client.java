package io.titix.sonder;

import java.net.Socket;
import java.util.List;

import io.titix.kiwi.check.Try;
import io.titix.sonder.internal.Communicator;
import io.titix.sonder.internal.SonderException;
import io.titix.sonder.internal.Transmitter;
import io.titix.sonder.internal.boot.EndpointBoot;
import io.titix.sonder.internal.boot.EndpointSignature;
import io.titix.sonder.internal.boot.OriginBoot;
import io.titix.sonder.internal.boot.OriginSignature;

/**
 * @author tix32 on 20-Dec-18
 */
public final class Client {

	private final Communicator communicator;

	public static Client run(String host, int port, String[] servicePackages) {
		OriginBoot originBoot = new OriginBoot(servicePackages);
		EndpointBoot endpointBoot = new EndpointBoot(servicePackages);
		return new Client(createSocket(host, port), originBoot, endpointBoot);
	}

	private Client(Socket socket, OriginBoot originBoot, EndpointBoot endpointBoot) {
		List<OriginSignature> origins = originBoot.getSignatures();
		List<EndpointSignature> endpoints = endpointBoot.getSignatures();
		communicator = new Communicator(new Transmitter(socket), origins, endpoints);
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

	private static Socket createSocket(String host, int port) {
		return Try.supply(() -> new Socket(host, port))
				.getOrElseThrow(
						exception -> new SonderException("Cannot connect to server " + host + ":" + port, exception));
	}
}
