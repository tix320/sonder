package com.gitlab.tixtix320.sonder.api.common.communication;

import java.io.IOException;
import java.io.Serializable;
import java.util.*;
import java.util.Map.Entry;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ValueNode;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.gitlab.tixtix320.sonder.internal.common.communication.InvalidHeaderException;

@JsonSerialize(using = Headers.HeadersSerializer.class)
@JsonDeserialize(using = Headers.HeadersDeserializer.class)
public final class Headers implements Serializable {
	private static final long serialVersionUID = 4379459480293747097L;

	private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

	public static final Headers EMPTY = new Headers(Collections.emptyMap());

	private final Map<String, Object> values;

	private Headers(Map<String, Object> values) {
		this.values = values;
	}

	public Object get(String key) {
		return values.get(key);
	}

	public ContentType getContentType() {
		return ContentType.valueOf(getNonNullString(Headers.CONTENT_TYPE));
	}

	public String getNonNullString(String key) {
		Object value = values.get(key);
		if (value instanceof String) {
			return (String) value;
		}
		throw new InvalidHeaderException(key, value, String.class);
	}

	public String getString(String key) {
		Object value = values.get(key);
		if (value == null || value instanceof String) {
			return (String) value;
		}
		throw new InvalidHeaderException(key, value, String.class);
	}

	public Number getNonNullNumber(String key) {
		Object value = values.get(key);
		if (value instanceof Number) {
			return (Number) value;
		}
		throw new InvalidHeaderException(key, value, Number.class);
	}

	public Boolean getBoolean(String key) {
		Object value = values.get(key);
		if (value == null || value instanceof Boolean) {
			return (Boolean) value;
		}
		throw new InvalidHeaderException(key, value, Boolean.class);
	}

	public boolean getNonNullBoolean(String key) {
		Object value = values.get(key);
		if (value instanceof Boolean) {
			return (Boolean) value;
		}
		throw new InvalidHeaderException(key, value, Boolean.class);
	}

	public Number getNumber(String key) {
		Object value = values.get(key);
		if (value == null || value instanceof Number) {
			return (Number) value;
		}
		throw new InvalidHeaderException(key, value, Number.class);
	}

	public HeadersBuilder compose() {
		return new HeadersBuilder(new HashMap<>(this.values));
	}

	public static HeadersBuilder builder() {
		return new HeadersBuilder(new HashMap<>());
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
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

		public HeadersBuilder contentType(ContentType contentType) {
			values.put(CONTENT_TYPE, contentType.name());
			return this;
		}

		public Headers build() {
			return new Headers(values);
		}
	}

	//	--------------------Known Keys
	public static final String PROTOCOL = "protocol";
	public static final String DESTINATION_CLIENT_ID = "destination-client-id";
	public static final String IS_INVOKE = "is-invoke";
	public static final String TRANSFER_KEY = "transfer-key";
	public static final String PATH = "path";
	public static final String SOURCE_CLIENT_ID = "source-client-id";
	public static final String NEED_RESPONSE = "need-response";
	public static final String TOPIC = "topic";
	public static final String TOPIC_ACTION = "topic-action";
	public static final String CONTENT_TYPE = "content-type";

	public static class HeadersSerializer extends StdSerializer<Headers> {

		public HeadersSerializer() {
			super(Headers.class);
		}

		@Override
		public void serialize(Headers headers, JsonGenerator gen, SerializerProvider provider)
				throws IOException {
			gen.writeStartObject();
			for (Map.Entry<String, Object> entry : headers.values.entrySet()) {
				gen.writeObjectField(entry.getKey(), entry.getValue());
			}
			gen.writeEndObject();
		}
	}

	public static class HeadersDeserializer extends StdDeserializer<Headers> {

		public HeadersDeserializer() {
			super(Headers.class);
		}

		@Override
		public Headers deserialize(JsonParser p, DeserializationContext ctxt)
				throws IOException, JsonProcessingException {
			JsonNode node = p.getCodec().readTree(p);

			if (!(node instanceof ObjectNode)) {
				throw new IllegalStateException(String.format("Headers must be JSON object, but was %s", node));
			}

			Iterator<Entry<String, JsonNode>> iterator = node.fields();
			Headers.HeadersBuilder builder = Headers.builder();
			while (iterator.hasNext()) {
				Map.Entry<String, JsonNode> entry = iterator.next();
				JsonNode value = entry.getValue();
				if (value instanceof ValueNode) { // ignore non primitive headers
					builder.header(entry.getKey(), JSON_MAPPER.convertValue(value, Object.class));
				}
			}

			return builder.build();
		}
	}
}
