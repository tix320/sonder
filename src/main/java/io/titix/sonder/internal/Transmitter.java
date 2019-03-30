package io.titix.sonder.internal;

import io.titix.kiwi.rx.Observable;
import io.titix.kiwi.rx.Subject;
import io.titix.kiwi.util.Threads;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public final class Transmitter {

    private final Lock writeLock = new ReentrantLock();

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
        listenTransfers();
    }

    void send(Transfer transfer) {
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

    Observable<Transfer> transfers() {
        return transfers.asObservable();
    }

    private void listenTransfers() {
        Threads.runDaemon(() -> {
            //noinspection InfiniteLoopStatement
            while (true) {
                Transfer transfer;
                try {
                    transfer = (Transfer) reader.readObject();
                }
                catch (IOException e) {
                    String message = e.getMessage();
                    if (message.contains("Connection reset")) {

                    }
                    throw new TransmitterException("Failed to receive transfer", e);
                }
                catch (ClassNotFoundException e) {
                    throw new TransmitterException("Failed to receive transfer", e);
                }
                transfers.next(transfer);
            }
        });

    }

    void close() {
        transfers.complete();
        try {
            socket.close();
        }
        catch (IOException e) {
            throw new TransmitterException("Cannot close socket", e);
        }
    }
}
