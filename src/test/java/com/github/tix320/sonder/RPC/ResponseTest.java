package com.github.tix320.sonder.RPC;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.tix320.sonder.api.common.rpc.RPCRemoteException;
import com.github.tix320.sonder.internal.common.rpc.exception.MethodInvocationException;
import com.github.tix320.sonder.internal.common.rpc.protocol.UnhandledErrorResponseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
		ClientService rpcService = rpcProtocol.getOrigin(ClientService.class);

		AtomicInteger value = new AtomicInteger(-1);
		AtomicBoolean errorReturned = new AtomicBoolean(false);

		rpcService.getAnyValue().subscribe(response -> {
			try {
				Integer integer = response.get();
				value.set(integer);
			} catch (Exception e) {
				throw new IllegalStateException();
			}

		});

		List<Throwable> exceptions = new CopyOnWriteArrayList<>();

		Thread.setDefaultUncaughtExceptionHandler((t, e) -> exceptions.add(e));

		Thread.sleep(1000);

		assertEquals(5, value.get());

		// -------------------------------

		value.set(-1);
		errorReturned.set(false);

		rpcService.throwAnyException().subscribe(response -> {
			try {
				//noinspection ResultOfMethodCallIgnored
				response.get();
				value.set(10);
			} catch (Exception e) {
				errorReturned.set(true);
			}
		});

		Thread.sleep(1000);

		assertEquals(-1, value.get());
		assertTrue(errorReturned.get());

		// -------------------------------

		value.set(-1);
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
