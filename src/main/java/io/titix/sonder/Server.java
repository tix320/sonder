package io.titix.sonder;

import java.io.IOException;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.titix.kiwi.check.Try;
import io.titix.kiwi.util.Threads;
import io.titix.sonder.internal.Communicator;
import io.titix.sonder.internal.SonderException;
import io.titix.sonder.internal.Transmitter;
import io.titix.sonder.internal.boot.EndpointBoot;
import io.titix.sonder.internal.boot.EndpointSignature;
import io.titix.sonder.internal.boot.OriginBoot;
import io.titix.sonder.internal.boot.OriginSignature;


/**
 * @author Tigran.Sargsyan on 11-Dec-18
 */
public final class Server {

	private final ExecutorService EXECUTOR = Executors.newCachedThreadPool();

	private final OriginBoot originBoot;

	private final EndpointBoot endpointBoot;

	private final ServerSocket serverSocket;

	private final Map<Long, Communicator> communicators;

	public static Server run(int port, String[] servicePackages) {
		ServerSocket serverSocket = Try.supply(() -> new ServerSocket(port))
				.getOrElseThrow((throwable) -> resolveMagicError(throwable, port));
		OriginBoot originBoot = new OriginBoot(servicePackages);
		EndpointBoot endpointBoot = new EndpointBoot(servicePackages);
		return new Server(serverSocket, originBoot, endpointBoot);
	}

	private Server(ServerSocket serverSocket, OriginBoot originBoot, EndpointBoot endpointBoot) {
		this.originBoot = originBoot;
		this.endpointBoot = endpointBoot;
		this.serverSocket = serverSocket;
		this.communicators = new ConcurrentHashMap<>();
		start();
	}

	public <T> T getService(Long clientId, Class<T> clazz) {
		Communicator communicator = communicators.get(clientId);
		if (communicator == null) {
			throw new IllegalArgumentException("Client by id " + clientId + " not found");
		}
		T service = communicator.getService(clazz);
		if (service == null) {
			throw new IllegalArgumentException("Service of " + clazz + " not found");
		}
		return service;
	}

	public void stop() {
		try {
			EXECUTOR.shutdownNow();
			serverSocket.close();
		}
		catch (IOException e) {
			throw new SonderException("Cannot close server socket.", e);
		}
	}

	private void start() {
		Threads.runAsync(() -> {
			try {
				//noinspection InfiniteLoopStatement
				while (true) {
					Socket socket = serverSocket.accept();

					Threads.runAsync(() -> {
						List<OriginSignature> origins = originBoot.getSignatures();
						List<EndpointSignature> endpoints = endpointBoot.getSignatures();
						Communicator communicator = new Communicator(new Transmitter(socket), origins, endpoints);
						communicators.put(communicator.id(), communicator);
					}, EXECUTOR);
				}
			}
			catch (IOException e) {
				throw new SonderException("Cannot accept socket", e);
			}
		}, EXECUTOR);
	}

	private static SonderException resolveMagicError(Throwable throwable, int port) {
		if (throwable instanceof BindException) {
			if (throwable.getMessage().contains("Address already in use")) {
				return new SonderException("Port " + port + " already in use");
			}
		}
		throw new SonderException("Cover this exception", throwable);
	}

}
