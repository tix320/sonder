package io.titix.sonder;

import java.io.IOException;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.titix.kiwi.check.Try;
import io.titix.kiwi.util.Threads;
import io.titix.sonder.internal.Communicator;
import io.titix.sonder.internal.Config;
import io.titix.sonder.internal.SonderException;
import io.titix.sonder.internal.boot.EndpointBoot;
import io.titix.sonder.internal.boot.OriginBoot;


/**
 * @author Tigran.Sargsyan on 11-Dec-18
 */
public final class Server {

	private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(Threads::daemon);

	private static final OriginBoot originBoot = new OriginBoot(Config.getServerBootPackages());

	private static final EndpointBoot endpointBoot = new EndpointBoot(Config.getServerBootPackages());

	private final ServerSocket serverSocket;

	private final Map<Long, Communicator> communicators;

	public Server(int port) {
		this.serverSocket = Try.supply(() -> new ServerSocket(port))
				.getOrElseThrow((throwable) -> resolveMagicError(throwable, port));
		this.communicators = new HashMap<>();
		start();
	}

	public <T> T getService(Long originId, Class<T> clazz) {
		T service = communicators.get(originId).getService(clazz);

		if (service == null) {
			throw new IllegalArgumentException("Instance of service " + clazz + " not found");
		}
		return service;
	}

	public void stop() {
		try {
			serverSocket.close();
		}
		catch (IOException e) {
			throw new SonderException("Cannot close server socket.", e);
		}
	}

	private void start() {
		Threads.runDaemon((() -> {
			try {
				//noinspection InfiniteLoopStatement
				while (true) {
					Socket socket = serverSocket.accept();

					Threads.runAsync(() -> {
						Communicator communicator = new Communicator(socket, originBoot, endpointBoot);
						communicators.put(communicator.id(), communicator);
					}, EXECUTOR);
				}
			}
			catch (IOException e) {
				throw new SonderException("Cannot accept socket", e);
			}
		}));
	}

	private SonderException resolveMagicError(Throwable throwable, int port) {
		if (throwable instanceof BindException) {
			if (throwable.getMessage().contains("Address already in use")) {
				return new SonderException("Port " + port + " already in use");
			}
		}
		throw new SonderException("Cover this exception", throwable);
	}

}
