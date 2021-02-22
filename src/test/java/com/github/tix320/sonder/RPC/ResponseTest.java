package com.github.tix320.sonder.RPC;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import com.github.tix320.sonder.internal.common.rpc.exception.MethodInvocationException;
import com.github.tix320.sonder.internal.common.rpc.exception.RPCRemoteException;
import com.github.tix320.sonder.internal.common.rpc.protocol.UnhandledErrorResponseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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
		ClientService rpcService = rpcProtocol.getOrigin(ClientService.class);

		AtomicBoolean valueReturned = new AtomicBoolean(false);
		AtomicBoolean errorReturned = new AtomicBoolean(false);

		rpcService.getAnyValue().subscribe(response -> {
			if (response.isSuccess()) {
				valueReturned.set(true);
			}
		});

		List<Throwable> exceptions = new CopyOnWriteArrayList<>();

		Thread.setDefaultUncaughtExceptionHandler((t, e) -> exceptions.add(e));

		Thread.sleep(1000);

		assertTrue(valueReturned.get());

		// -------------------------------

		valueReturned.set(false);
		errorReturned.set(false);

		rpcService.throwAnyException().subscribe(response -> {
			if (response.isSuccess()) {
				valueReturned.set(true);
			} else {
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

		assertEquals(3, exceptions.size());

		Throwable first = exceptions.get(0);
		assertEquals(MethodInvocationException.class, first.getClass());
		assertEquals(RuntimeException.class, first.getCause().getClass());
		assertEquals("any-exception", first.getCause().getMessage());

		Throwable second = exceptions.get(1);
		assertEquals(MethodInvocationException.class, second.getClass());
		assertEquals(RuntimeException.class, second.getCause().getClass());
		assertEquals("without-handle", second.getCause().getMessage());

		Throwable third = exceptions.get(2);
		assertEquals(UnhandledErrorResponseException.class, third.getClass());
		assertEquals(RPCRemoteException.class, third.getCause().getClass());
	}
}
