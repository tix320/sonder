package com.github.tix320.sonder.RPC;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Tigran Sargsyan on 14-Jul-20.
 */
public class ResponseTest extends BaseTest {

	@Override
	@BeforeEach
	public void setUp() throws IOException {
		super.setUp();
	}

	@Test
	public void test() throws InterruptedException, IOException {
		ClientService rpcService = originInstanceResolver.get(ClientService.class);

		AtomicBoolean valueReturned = new AtomicBoolean(false);
		AtomicBoolean errorReturned = new AtomicBoolean(false);

		rpcService.getAnyValue().subscribe(response -> {
			if (response.isSuccess()) {
				valueReturned.set(true);
			}
		});

		Thread.sleep(1000);

		assertTrue(valueReturned.get());

		// -------------------------------

		valueReturned.set(false);
		errorReturned.set(false);

		rpcService.throwAnyException().subscribe(response -> {
			if (response.isSuccess()) {
				valueReturned.set(true);
			}
			else {
				errorReturned.set(true);
			}
		});

		Thread.sleep(1000);

		assertFalse(valueReturned.get());
		assertTrue(errorReturned.get());

		// -------------------------------

		valueReturned.set(false);
		errorReturned.set(false);

		rpcService.throwAnyExceptionWithoutHandle();

		Thread.sleep(1000);
	}
}
