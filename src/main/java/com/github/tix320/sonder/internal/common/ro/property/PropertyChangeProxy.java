package com.github.tix320.sonder.internal.common.ro.property;

import java.util.function.Consumer;

import com.github.tix320.kiwi.api.reactive.observable.Observable;
import com.github.tix320.kiwi.api.reactive.property.Property;
import com.github.tix320.kiwi.api.reactive.property.ReadOnlyProperty;

public class PropertyChangeProxy<T> implements Property<T> {

	private final Property<T> property;

	private final Consumer<T> changeListener;

	public PropertyChangeProxy(Property<T> property, Consumer<T> changeListener) {
		this.property = property;
		this.changeListener = changeListener;
	}

	@Override
	public void set(T value) {
		changeListener.accept(value);
	}

	@Override
	public ReadOnlyProperty<T> toReadOnly() {
		return property.toReadOnly();
	}

	@Override
	public T get() {
		return property.get();
	}

	@Override
	public Observable<T> asObservable() {
		return property.asObservable();
	}
}
