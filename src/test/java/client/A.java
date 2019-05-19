package client;

import com.gitlab.tixtix320.kiwi.observable.Observable;
import io.titix.sonder.Origin;
import io.titix.sonder.extra.ClientID;

/**
 * @author Tigran.Sargsyan on 30-Jan-19
 */
@Origin("chat")
public interface A {

	@Origin("message")
	Observable<String> send(String message, @ClientID long id);
}
