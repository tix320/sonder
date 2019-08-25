package client;

import java.util.List;

import com.gitlab.tixtix320.kiwi.observable.Observable;
import io.titix.sonder.Origin;
import io.titix.sonder.extra.ClientID;

/**
 * @author Tigran.Sargsyan on 30-Jan-19
 */
@Origin("super")
public interface A {

	@Origin("")
	Observable<Integer> send(String message );
}
