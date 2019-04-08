package io.titix.sonder.internal;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Exchanger;
import java.util.function.BiFunction;

import io.titix.kiwi.check.Try;
import io.titix.kiwi.rx.Observable;
import io.titix.kiwi.rx.Subject;
import io.titix.kiwi.util.IDGenerator;

/**
 * @author Tigran.Sargsyan on 26-Dec-18
 */
public final class Communicator {

	private final Transmitter transmitter;

	private final IDGenerator transferIdGenerator;

	private final Map<Long, Exchanger<Object>> exchangers;

	private final BiFunction<Headers, Object[], Object> requestHandler;

	public Communicator(Transmitter transmitter, BiFunction<Headers, Object[], Object> requestHandler) {
		this.transmitter = transmitter;
		this.exchangers = new ConcurrentHashMap<>();
		this.transferIdGenerator = new IDGenerator();
		this.requestHandler = requestHandler;
		handleIncomingRequests();
	}

	public Observable<?> sendRequest(Headers headers, Object[] content) {
		long transferKey = transferIdGenerator.next();

		headers = headers.compose(
				Headers.builder().header("transfer-key", transferKey).header("need-response", true).build());
		Transfer transfer = new Transfer(headers, content);

		Subject<Object> result = Subject.single();
		Exchanger<Object> exchanger = new Exchanger<>();
		exchangers.put(transferKey, exchanger);
		CompletableFuture.runAsync(() -> Try.runAndRethrow(() -> result.next(exchanger.exchange(null))))
				.exceptionallyAsync(throwable -> {
					throwable.getCause().printStackTrace();
					return null;
				});
		CompletableFuture.runAsync(() -> this.transmitter.send(transfer)).exceptionallyAsync(throwable -> {
			throwable.getCause().printStackTrace();
			return null;
		});

		return result.asObservable();
	}

	public void sendUnresponsiveRequest(Headers headers, Object[] content) {
		headers = headers.compose(Headers.builder().header("need-response", false).build());

		Transfer transfer = new Transfer(headers, content);

		CompletableFuture.runAsync(() -> this.transmitter.send(transfer)).exceptionallyAsync(throwable -> {
			throwable.getCause().printStackTrace();
			return null;
		});
	}

	public void close() {
		transmitter.close();
	}

	private void handleIncomingRequests() {
		transmitter.transfers().subscribe(this::resolveTransfer);
	}

	private void resolveTransfer(Transfer transfer) {
		Headers headers = transfer.headers;
		boolean isResponse = Objects.requireNonNullElse(headers.getBoolean("is-response"), false);
		if (isResponse) {
			processResponse(transfer);
		}
		else {
			processRequest(transfer);
		}
	}

	private void processRequest(Transfer transfer) {
		Headers headers = transfer.headers;
		Object[] args = (Object[]) transfer.content;
		Object result = requestHandler.apply(headers, args);

		boolean needResponse = headers.getBoolean("need-response");
		if (needResponse) {
			Long transferKey = headers.getLong("transfer-key");
			sendResponse(transferKey, result);
		}
	}

	private void processResponse(Transfer transfer) {
		Long transferKey = transfer.headers.getLong("transfer-key");
		exchangers.computeIfPresent(transferKey, (key, exchanger) -> {
			Try.run(() -> exchanger.exchange(transfer.content)).rethrow(InternalException::new);
			return null;
		});
	}

	private void sendResponse(Long transferKey, Object content) {
		Headers headers = Headers.builder().header("transfer-key", transferKey).header("is-response", true).build();
		this.transmitter.send(new Transfer(headers, content));
	}
}
