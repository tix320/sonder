package com.github.tix320.sonder.internal.common.ro.property;

import java.util.List;
import java.util.function.Consumer;

import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.kiwi.api.reactive.stock.ReadOnlyStock;
import com.github.tix320.kiwi.api.reactive.stock.Stock;

public class StockChangeProxy<T> implements Stock<T> {

	private final Stock<T> stock;

	private final Consumer<T> changeListener;

	public StockChangeProxy(Stock<T> stock, Consumer<T> changeListener) {
		this.stock = stock;
		this.changeListener = changeListener;
	}

	@Override
	public void add(T value) {
		changeListener.accept(value);
	}

	@Override
	public void addAll(Iterable<T> values) {
		for (T value : values) {
			changeListener.accept(value);
		}
	}

	@Override
	public ReadOnlyStock<T> toReadOnly() {
		return stock.toReadOnly();
	}

	@Override
	public List<T> list() {
		return stock.list();
	}

	@Override
	public Observable<T> asObservable() {
		return stock.asObservable();
	}
}
