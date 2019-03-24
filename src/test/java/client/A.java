package client;

import java.util.List;

import io.titix.kiwi.rx.Observable;
import io.titix.sonder.Origin;

/**
 * @author Tigran.Sargsyan on 30-Jan-19
 */
@Origin("foo")
public interface A {

	@Origin("lol")
	Observable<Integer> foo(List<?> dsd);
}
