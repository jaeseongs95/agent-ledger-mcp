package org.pgledger.hook;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and writes the host JSON with Jackson data binding: the host events and the answers are records.
 *
 * <p>Only the tool arguments are arbitrary JSON chosen by the model. They are the one part that stays a Jackson
 * tree, so the round trip keeps what the host sent: integers beyond long range stay big integers, decimals are
 * kept as {@code BigDecimal} with their trailing zeros (never a double), strings and unknown fields are untouched.
 * A number is written back by value: {@code 1e999} becomes {@code 1E+999} and {@code -0} becomes {@code 0}.
 *
 * <p>The {@link JsonMapper} is the one bean that Spring Boot configures (principle 3.7). What this codec needs beyond
 * the Boot defaults is set in {@code application.properties} under {@code spring.jackson}: the nesting depth limits,
 * decimals as {@code BigDecimal} with their trailing zeros, text after the first JSON value as an error (as with
 * {@code JSON.parse}), and the answer {@code {}} as a record without components. Unknown fields of the host events are
 * ignored, which is a Boot default.
 */
@Component
public final class JsonCodec {

    private static final Logger LOG = LoggerFactory.getLogger(JsonCodec.class);

    private final JsonMapper mapper;

    JsonCodec(JsonMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Binds one host event. A JSON document that is valid but not an object of the host (null, an array, a number,
     * a string) is not an event: the result is {@code null}.
     *
     * @throws JacksonException if the bytes are not exactly one valid JSON document
     */
    <T> T readEvent(byte[] json, Class<T> type) throws JacksonException {
        try {
            return mapper.readValue(json, type);
        } catch (DatabindException notThisShape) {
            if (isOneJsonDocument(json)) {
                LOG.debug("the JSON document is not an event of the host", notThisShape);
                return null;
            }
            throw notThisShape;
        }
    }

    /** Binds a JSON object that is already a tree (the arguments of a wrapped call); empty if it is not an object. */
    <T> Optional<T> bindObject(JsonNode node, Class<T> type) {
        if (node == null || !node.isObject()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.treeToValue(node, type));
    }

    /** Parses UTF-8 (or UTF-16/32 with a byte order mark) JSON text of the tool arguments into a tree. */
    JsonNode read(byte[] json) throws JacksonException {
        return mapper.readTree(json);
    }

    /**
     * The tree as the plain Java model of JSON that {@link org.pgledger.attestation.ArgumentsDigest} reads: maps in
     * the order of the document, lists, texts, numbers (big integers and {@code BigDecimal} as the codec reads them),
     * booleans and {@code null}.
     */
    Object plain(JsonNode node) throws JacksonException {
        return mapper.treeToValue(node, Object.class);
    }

    /** UTF-8 bytes of the compact JSON text, independent of the JVM default charset. */
    byte[] write(Object value) throws JacksonException {
        return mapper.writeValueAsBytes(value);
    }

    /** The compact JSON text, for a host that carries JSON inside a JSON string. */
    String writeText(JsonNode node) throws JacksonException {
        return mapper.writeValueAsString(node);
    }

    /** Whether the bytes are exactly one valid JSON value or nothing, checked token by token without a tree. */
    private boolean isOneJsonDocument(byte[] json) {
        try (JsonParser parser = mapper.createParser(json)) {
            if (parser.nextToken() == null) {
                return true;
            }
            parser.skipChildren();
            return parser.nextToken() == null;
        }
    }
}
