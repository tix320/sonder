package com.gitlab.tixtix320.sonder.server;

import com.gitlab.tixtix320.sonder.api.server.Sonder;

/**
 * @author Tigran.Sargsyan on 24-Jan-19
 */
public final class ServerTest {

	public static void main(String[] args) throws InterruptedException {
		Sonder sonder = Sonder.withBuiltInProtocols(Integer.parseInt(args[0]), "com.gitlab.tixtix320.sonder.server");

		TestOrigin service = sonder.getRPCService(TestOrigin.class);

		Thread.sleep(500000);
		//service.foo("hello", -9223372036854775808L).subscribe(object -> System.out.println(object));
	}
}
