package server;

import io.titix.kiwi.rx.Observable;
import io.titix.sonder.Origin;
import io.titix.sonder.extra.ClientID;

/**
 * @author Tigran.Sargsyan on 30-Jan-19
 */
@Origin("dfoo")
public interface TestOrigin {

	@Origin("ldsaol")
	Observable<Integer> foo(String message, @ClientID long id);
}
