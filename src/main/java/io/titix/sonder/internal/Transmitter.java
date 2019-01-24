package io.titix.sonder.internal;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

final class Transmitter {

	private final Lock writeLock = new ReentrantLock();

	private final Socket socket;

	private final ObjectOutputStream writer;

	private final ObjectInputStream reader;

	Transmitter(Socket socket) {
		this.socket = socket;
		try {
			this.writer = new ObjectOutputStream(socket.getOutputStream());
			this.reader = new ObjectInputStream(socket.getInputStream());
		}
		catch (IOException e) {
			throw new TransmitterException("Transmitter creating error.", e);
		}
	}

	void send(Transfer transfer) {
		writeLock.lock();
		try {
			writer.writeObject(transfer);
		}
		catch (IOException e) {
			close();
			throw new TransmitterException("Failed to send transfer", e);
		} finally {
			writeLock.unlock();
		}
	}

	Transfer receive() {
		Transfer transfer;
		try {
			transfer = (Transfer) reader.readObject();
		}
		catch (IOException e) {
			String message = e.getMessage();
			if(message.contains("Connection reset")){

			}
			throw new TransmitterException("Failed to receive transfer", e);
		}
		catch (ClassNotFoundException e) {
			throw new TransmitterException("Failed to receive transfer", e);
		}
		return transfer;
	}

	void close() {
		try {
			socket.close();
		}
		catch (IOException e) {
			throw new TransmitterException("Cannot close socket", e);
		}
	}
}
