package io.titix.sonder.internal;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

public final class Headers implements Serializable {
	private static final long serialVersionUID = 4379459480293747097L;

	public static final Headers EMPTY = new Headers(Map.of());

	private final Map<String, Object> values;

	private Headers(Map<String, Object> values) {
		this.values = values;
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

	public HeadersBuilder compose() {
		return new HeadersBuilder(new HashMap<>(this.values));
	}

	public static HeadersBuilder builder() {
		return new HeadersBuilder(new HashMap<>());
	}

	public static final class HeadersBuilder {
		private final Map<String, Object> values;

		public HeadersBuilder(Map<String, Object> values) {
			this.values = values;
		}

		public HeadersBuilder header(String key, Object value) {
			values.put(key, value);
			return this;
		}

		public HeadersBuilder delete(String key, Object value) {
			values.remove(key, value);
			return this;
		}

		public Headers build() {
			return new Headers(values);
		}
	}
}
