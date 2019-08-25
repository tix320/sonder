package io.titix.sonder.internal;

import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

@JsonSerialize(using = Headers.HeadersSerializer.class)
@JsonDeserialize(using = Headers.HeadersDeserializer.class)
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

		public HeadersBuilder delete(String key) {
			values.remove(key);
			return this;
		}

		public Headers build() {
			return new Headers(values);
		}
	}

	//	--------------------Keys
	public static final String DESTINATION_CLIENT_ID = "destination-client-id";
	public static final String IS_RESPONSE = "is-response";
	public static final String TRANSFER_KEY = "transfer-key";
	public static final String PATH = "path";
	public static final String SOURCE_CLIENT_ID = "source-client-id";
	public static final String NEED_RESPONSE = "need-response";

	public static class HeadersSerializer extends StdSerializer<Headers> {

		protected HeadersSerializer(Class<Headers> t) {
			super(t);
		}

		@Override
		public void serialize(Headers headers, JsonGenerator gen, SerializerProvider provider) throws IOException {
			ObjectMapper mapper = new ObjectMapper();
			mapper.writeValue(gen, headers.values);
		}
	}

	public static class HeadersDeserializer extends StdDeserializer<Headers> {

		protected HeadersDeserializer(Class<?> vc) {
			super(vc);
		}

		@Override
		public Headers deserialize(JsonParser p,
								   DeserializationContext ctxt) throws IOException, JsonProcessingException {
			Map<String, Object> values = p.readValueAs(new TypeReference<Map<String, Object>>() {});
			return new Headers(values);
		}
	}
}
