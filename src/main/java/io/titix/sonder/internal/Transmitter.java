package io.titix.sonder.internal;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.gitlab.tixtix320.kiwi.check.Try;
import com.gitlab.tixtix320.kiwi.observable.Observable;
import com.gitlab.tixtix320.kiwi.observable.subject.Subject;

public final class Transmitter {

	private final Lock writeLock = new ReentrantLock();

	private final AtomicBoolean started = new AtomicBoolean(false);

	private final Socket socket;

	private final ObjectOutputStream writer;

	private final ObjectInputStream reader;

	private final Subject<Transfer> transfers;

	public Transmitter(Socket socket) {
		this.socket = socket;
		try {
			this.writer = new ObjectOutputStream(socket.getOutputStream());
			this.reader = new ObjectInputStream(socket.getInputStream());
		}
		catch (IOException e) {
			throw new TransmitterException("Transmitter creating error.", e);
		}
		this.transfers = Subject.single();
	}

	public void send(Transfer transfer) {
		try {
			writeLock.lock();
			writer.writeObject(transfer);
		}
		catch (IOException e) {
			close();
			throw new TransmitterException("Failed to send transfer", e);
		}
		finally {
			writeLock.unlock();
		}
	}

	public Observable<Transfer> transfers() {
		return transfers.asObservable();
	}

	public void handleIncomingTransfers() {
		if (started.get()) {
			throw new IllegalStateException("Transmitter already started handling");
		}
		started.set(true);
		CompletableFuture.runAsync(() -> {
			//noinspection InfiniteLoopStatement
			while (true) {
				Transfer transfer;

				transfer = (Transfer) Try.supplyAndGet(reader::readObject);

				transfers.next(transfer);
			}
		}).exceptionallyAsync(throwable -> {
			throwable.getCause().printStackTrace();
			return null;
		});

	}

	public void close() {
		transfers.complete();
		try {
			socket.close();
		}
		catch (IOException e) {
			throw new TransmitterException("Cannot close socket", e);
		}
	}
}
