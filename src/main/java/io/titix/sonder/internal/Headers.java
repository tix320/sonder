package io.titix.sonder.internal;

import java.io.Serializable;
import java.util.Map;

public final class Headers implements Serializable {

	private final Map<String, Object> values;

	private Headers(Map<String, Object> values) {
		this.values = values;
	}

	public <T> T getHeader(String key, Class<T> type) {
		return type.cast(values.get(key));
	}

	public String getPath() {
		return (String) values.get("path");
	}

	public Long getId() {
		return (Long) values.get("id");
	}

	public Boolean isResponse() {
		return (Boolean) values.get("isResponse");
	}

	public Boolean needResponse() {
		return (Boolean) values.get("needResponse");
	}

	public static HeadersBuilder builder() {
		return new HeadersBuilder();
	}

	public static final class HeadersBuilder {
		private String path = "";

		private Long id = 0L;

		private Boolean isResponse = Boolean.FALSE;

		private Boolean needResponse = Boolean.TRUE;

		public HeadersBuilder path(String path) {
			this.path = path;
			return this;
		}

		public HeadersBuilder id(Long id) {
			this.id = id;
			return this;
		}

		public HeadersBuilder isResponse(boolean isResponse) {
			this.isResponse = isResponse;
			return this;
		}

		public HeadersBuilder needResponse(boolean needResponse) {
			this.needResponse = needResponse;
			return this;
		}

		public Headers build() {
			return new Headers(Map.of("path", path, "id", id, "isResponse", isResponse, "needResponse", needResponse));
		}
	}
}
