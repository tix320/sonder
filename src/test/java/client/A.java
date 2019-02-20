package client;

import java.util.concurrent.CompletableFuture;

import io.titix.sonder.Origin;
import io.titix.sonder.Response;

/**
 * @author Tigran.Sargsyan on 30-Jan-19
 */
@Origin("foo")
public interface A {

	@Origin("lol")
	@Response(true)
	CompletableFuture<Integer> foo();
}
