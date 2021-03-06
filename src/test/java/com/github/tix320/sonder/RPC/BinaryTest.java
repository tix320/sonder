package com.github.tix320.sonder.RPC;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author Tigran Sargsyan on 03-Jun-20.
 */
public class BinaryTest extends BaseTest {

	@Override
	@BeforeEach
	public void setUp() throws IOException {
		super.setUp();
	}

	@Test
	public void test() throws InterruptedException, IOException {
		ClientService rpcService = rpcProtocol.getOrigin(ClientService.class);

		AtomicInteger responseHolder = new AtomicInteger();

		rpcService.putByes(new byte[]{10, 20, 30}).subscribe(responseHolder::set);
		Thread.sleep(500);
		assertEquals(60, responseHolder.get());

		rpcService.putByes(new byte[]{50, 60, 70}).subscribe(responseHolder::set);
		Thread.sleep(500);
		assertEquals(180, responseHolder.get());

		rpcService.putByes(new byte[]{23, 12, 32}).subscribe(responseHolder::set);
		Thread.sleep(500);
		assertEquals(67, responseHolder.get());
	}
}
