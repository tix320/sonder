package com.gitlab.tixtix320.sonder.api.common.communication;

import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

@JsonSerialize(using = Headers.HeadersSerializer.class)
public final class Headers implements Serializable {
	private static final long serialVersionUID = 4379459480293747097L;

	private final Map<String, Object> values;

	private Headers(Map<String, Object> values) {
		this.values = values;
	}

	public Object get(String key) {
		return values.get(key);
	}

	public HeadersBuilder compose() {
		return new HeadersBuilder(new HashMap<>(this.values));
	}

	public static HeadersBuilder builder() {
		return new HeadersBuilder(new HashMap<>());
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		Headers headers = (Headers) o;
		return values.equals(headers.values);
	}

	@Override
	public int hashCode() {
		return Objects.hash(values);
	}

	@Override
	public String toString() {
		return values.toString();
	}

	public static final class HeadersBuilder {
		private final Map<String, Object> values;

		private HeadersBuilder(Map<String, Object> values) {
			this.values = values;
		}

		public HeadersBuilder header(String key, Object value) {
			values.put(key, value);
			return this;
		}

		public Headers build() {
			return new Headers(values);
		}
	}

	//	--------------------Known Keys
	public static final String PROTOCOL = "protocol";
	public static final String DESTINATION_CLIENT_ID = "destination-client-id";
	public static final String IS_RESPONSE = "is-response";
	public static final String TRANSFER_KEY = "transfer-key";
	public static final String PATH = "path";
	public static final String SOURCE_CLIENT_ID = "source-client-id";
	public static final String NEED_RESPONSE = "need-response";
	public static final String TOPIC = "topic";
	public static final String TOPIC_ACTION = "topic-action";

	public static class HeadersSerializer extends StdSerializer<Headers> {

		public HeadersSerializer() {
			super(Headers.class);
		}

		@Override
		public void serialize(Headers headers, JsonGenerator gen, SerializerProvider provider) throws IOException {
			gen.writeStartObject();
			for (Map.Entry<String, Object> entry : headers.values.entrySet()) {
				gen.writeObjectField(entry.getKey(), entry.getValue());
			}
			gen.writeEndObject();
		}
	}
}
