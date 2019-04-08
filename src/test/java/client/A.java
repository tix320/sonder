package client;

import io.titix.kiwi.rx.Observable;
import io.titix.sonder.Origin;
import io.titix.sonder.extra.ClientID;

/**
 * @author Tigran.Sargsyan on 30-Jan-19
 */
@Origin("foo")
public interface A {

	@Origin("lol")
	Observable<Integer> foo(String message, @ClientID long id);
}
