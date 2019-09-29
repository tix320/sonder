package com.gitlab.tixtix320.sonder.server;

import java.util.List;

import com.gitlab.tixtix320.sonder.api.server.Sonder;

/**
 * @author Tigran.Sargsyan on 24-Jan-19
 */
public final class ServerTest {

	public static void main(String[] args) throws InterruptedException {
		Sonder sonder = Sonder.run(Integer.parseInt(args[0]), List.of("com.gitlab.tixtix320.sonder.server"),
				List.of("com.gitlab.tixtix320.sonder.server"));

		TestOrigin service = sonder.getService(TestOrigin.class);

		Thread.sleep(500000);
		//service.foo("hello", -9223372036854775808L).subscribe(object -> System.out.println(object));
	}
}
