package io.github.backaad.mobilemoney.internal;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Shared Jackson mapper. Jackson types never appear in the public API. */
public final class Json {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            // Duplicate keys are rejected: they are a classic way to smuggle a second value
            // past a validator that reads the first one.
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private Json() {
    }

    /**
     * Parses a JSON document.
     *
     * @param bytes UTF-8 JSON
     * @return the tree
     * @throws JacksonException if the input is not valid JSON
     */
    public static JsonNode parse(byte[] bytes) {
        return MAPPER.readTree(bytes);
    }

    /**
     * Creates an empty JSON object.
     *
     * @return a new object node
     */
    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    /**
     * Serializes a tree to UTF-8 bytes.
     *
     * @param node the tree
     * @return the JSON bytes
     */
    public static byte[] write(JsonNode node) {
        return MAPPER.writeValueAsBytes(node);
    }

    /**
     * Returns a text field of an object, or {@code null} when it is absent, null or not a scalar.
     *
     * @param node the object
     * @param field field name
     * @return the value as text, or {@code null}
     */
    public static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isValueNode()) {
            return null;
        }
        return value.asString();
    }
}
