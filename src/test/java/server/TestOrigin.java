package server;

import com.gitlab.tixtix320.kiwi.api.observable.Observable;
import io.titix.sonder.Origin;
import io.titix.sonder.extra.ClientID;

/**
 * @author Tigran.Sargsyan on 30-Jan-19
 */
@Origin("super")
public interface TestOrigin {

	@Origin("")
	Observable<Integer> foo(String message, @ClientID long id);
}
