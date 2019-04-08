package io.titix.sonder.internal;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class Headers implements Serializable {
	private static final long serialVersionUID = 4379459480293747097L;

	public static final Headers EMPTY = new Headers(Map.of());

	private final Map<String, Object> values;

	private Headers(Map<String, Object> values) {
		this.values = Collections.unmodifiableMap(values);
	}

	public <T> T get(String key, Class<T> type) {
		return type.cast(values.get(key));
	}

	public String getString(String key) {
		return (String) values.get(key);
	}

	public Boolean getBoolean(String key) {
		return (Boolean) values.get(key);
	}

	public Long getLong(String key) {
		return (Long) values.get(key);
	}

	public Headers compose(Headers headers) {
		Map<String, Object> newValues = new HashMap<>();
		newValues.putAll(this.values);
		newValues.putAll(headers.values);
		return new Headers(newValues);
	}

	public static HeadersBuilder builder() {
		return new HeadersBuilder();
	}

	public static final class HeadersBuilder {
		private final Map<String, Object> values = new HashMap<>();

		public HeadersBuilder header(String key, Object value) {
			values.put(key, value);
			return this;
		}

		public Headers build() {
			return new Headers(values);
		}
	}
}
