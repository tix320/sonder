package com.github.tix320.sonder.RPCAndRO;

import com.github.tix320.kiwi.api.reactive.property.Property;
import com.github.tix320.kiwi.api.reactive.stock.Stock;

public interface RemoteObjectInterface {

	String foo(String message);

	Property<Integer> count();

	Stock<String> texts();
}
