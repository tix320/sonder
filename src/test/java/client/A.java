package client;

import io.titix.sonder.Origin;
import io.titix.sonder.extra.ClientID;

/**
 * @author Tigran.Sargsyan on 30-Jan-19
 */
@Origin("chat")
public interface A {

	@Origin("message")
	void send(String message, @ClientID long id);
}
