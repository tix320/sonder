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
import io.titix.sonder.internal.Boot;
import io.titix.sonder.internal.Communicator;
import io.titix.sonder.internal.Config;


/**
 * @author Tigran.Sargsyan on 11-Dec-18
 */
public final class Server {

	private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(Threads::daemon);

	private static final Boot boot = new Boot(Config.getServerBootPackages());

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
			throw new MagicException("Cannot close server socket.", e);
		}
	}

	private void start() {
		Threads.runDaemon((() -> {
			try {
				//noinspection InfiniteLoopStatement
				while (true) {
					Socket socket = serverSocket.accept();

					Threads.runAsync(() -> {
						Communicator communicator = new Communicator(socket, boot);
						communicators.put(communicator.id(), communicator);
					}, EXECUTOR);
				}
			}
			catch (IOException e) {
				throw new RuntimeException("Cannot accept socket", e);
			}
		}));
	}

	private MagicException resolveMagicError(Throwable throwable, int port) {
		if (throwable instanceof BindException) {
			if (throwable.getMessage().contains("Address already in use")) {
				return new MagicException("Port " + port + "already in use");
			}
		}
		throw new IllegalStateException("Cover this exception", throwable);
	}

}
