package com.github.tix320.sonder.RPCAndRO;

import com.github.tix320.kiwi.api.reactive.property.Property;
import com.github.tix320.kiwi.api.reactive.stock.Stock;

public class RemoteObjectImpl implements RemoteObjectInterface {

	private final Property<Integer> countProperty = Property.forObject(12);

	private final Stock<String> textsStock = Stock.forObject();

	@Override
	public String foo(String message) {
		return message.repeat(2);
	}

	@Override
	public Property<Integer> count() {
		return countProperty;
	}

	@Override
	public Stock<String> texts() {
		return textsStock;
	}
}
