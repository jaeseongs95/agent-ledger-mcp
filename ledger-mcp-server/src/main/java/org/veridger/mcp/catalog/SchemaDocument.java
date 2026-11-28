// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.springframework.util.StringUtils;

/**
 * A JSON schema of a tool as the MCP SDK holds it (a nested map of keywords; the SDK's {@code Tool} offers no other
 * form) with the few edits the catalog makes: close every object, restrict a property to a list of values, set a
 * description, resolve the descriptions, allow null in an output. The map form is confined to this class.
 *
 * <p>A property is addressed by a path of property names separated by dots; an array item is the name followed by
 * {@code []}. A property that is a {@code $ref} into {@code $defs} is followed to its definition.
 *
 * <p><b>Why the schema is edited here</b> (principle 2.1: the answer to "does Spring offer it?" is recorded here).
 * Spring AI makes the input schema of an {@code @McpTool} method once, with a fixed generator
 * ({@code McpJsonSchemaGenerator}, victools with the Jackson and Swagger modules), and offers no hook to change the
 * result: the schema has no {@code additionalProperties} (spike report c8), it cannot list values that live in the
 * database and change while the server runs, and its descriptions are the annotation texts, not message-bundle
 * texts. The MCP SDK (2.1.0) takes a tool definition as given; {@code McpSyncServerCustomizer} changes the server
 * specification, and the list filter ({@code McpSyncListFilter}) only decides whether a tool is visible. Evidence:
 * the bytecode of spring-ai-mcp-annotations 2.0.2, spring-ai-autoconfigure-mcp-server-common 2.0.2 and mcp-core
 * 2.1.0 ({@code javap}), and the spike report (SPIKE_REPORT.md, b and c).
 *
 * <p>Document (read): Spring AI reference "MCP Annotations - Server"
 * (https://docs.spring.io/spring-ai/reference/api/mcp/mcp-annotations-server.html). It does not describe a
 * customization of the input schema ({@code additionalProperties}, enumerations); the attributes of
 * {@code @McpToolParam} are only {@code description} and {@code required}: the documentation has no such feature.
 * Its section on dynamic schemas says "Tools can accept {@code CallToolRequest} for runtime schema handling", that is
 * the tool takes {@code request.arguments()} (a {@code Map<String, Object>}) and handles the arguments itself; a
 * schema that changes while the server runs, like the common codes here, is handled at the arguments in the same way
 * the official documentation shows. The MCP Java SDK javadoc was not opened; the SDK statements rest on {@code javap}.
 * The map form is the SDK's own type for a schema ({@code Tool.inputSchema()}); this class and {@code LiveSchema} are
 * the only places that hold it, and the edits are the minimum the catalog needs.
 */
final class SchemaDocument {

    private static final String PROPERTIES = "properties";
    private static final String DESCRIPTION = "description";
    private static final String DEFS = "$defs";

    private final Map<String, Object> root;

    private SchemaDocument(Map<String, Object> root) {
        this.root = root;
    }

    /** A deep copy of the schema of the SDK, so the original (the base of later builds) is never edited. */
    static SchemaDocument copyOf(Map<String, Object> schema) {
        return new SchemaDocument(copy(schema));
    }

    Map<String, Object> toSdk() {
        return root;
    }

    /** No object accepts a key that its schema does not list; an object that already says what it accepts keeps it. */
    void closeObjects() {
        close(root);
    }

    /** Restricts the property to the values; no values leaves it as it is. */
    void restrict(String path, List<String> values) {
        if (!values.isEmpty()) {
            node(path).ifPresent(node -> node.put("enum", new ArrayList<>(values)));
        }
    }

    Optional<String> description(String path) {
        return node(path).map(node -> node.get(DESCRIPTION)).filter(String.class::isInstance)
                .map(String.class::cast);
    }

    void describe(String path, String text) {
        node(path).ifPresent(node -> node.put(DESCRIPTION, text));
    }

    void addResponseOptions(String fieldsDescription, String structuredDescription) {
        Map<String, Object> properties = map(root.computeIfAbsent(PROPERTIES, ignored -> new LinkedHashMap<>()));
        properties.put(ResultOptions.FIELDS, copy(Map.of("type", "array", "minItems", 1, "maxItems", ResultOptions.MAX_FIELDS,
                "items", Map.of("type", "string", "maxLength", ResultOptions.MAX_PATH_LENGTH),
                DESCRIPTION, fieldsDescription)));
        properties.put(ResultOptions.STRUCTURED_ONLY, copy(Map.of("type", "boolean", DESCRIPTION, structuredDescription)));
    }

    boolean supportsField(String path) {
        return supports(root, StringUtils.delimitedListToStringArray(path, "."), 0);
    }

    private boolean supports(Map<String, Object> schema, String[] path, int index) {
        if (index == path.length) {
            return true;
        }
        Map<String, Object> current = resolve(schema);
        if (current.isEmpty()) {
            return true;
        }
        Map<String, Object> item = map(current.get("items"));
        if (item != null) {
            return supports(item, path, index);
        }
        Map<String, Object> properties = map(current.get(PROPERTIES));
        Map<String, Object> field = properties == null ? null : map(properties.get(path[index]));
        if (field != null) {
            return supports(field, path, index + 1);
        }
        Object additional = current.get("additionalProperties");
        if (Boolean.TRUE.equals(additional) || "object".equals(current.get("type")) && properties == null
                && additional == null) {
            return true;
        }
        Map<String, Object> additionalType = map(additional);
        return additionalType != null && supports(additionalType, path, index + 1);
    }

    /** Replaces every description with the result of the operator (the message keys become their texts). */
    void rewriteDescriptions(UnaryOperator<String> resolve) {
        rewrite(root, resolve);
    }

    /** Lets every property of an output hold null, which the output records produce for an absent value. */
    void allowNullProperties() {
        nullable(root);
    }

    void allowFieldSelection() {
        optional(root);
    }

    private static void optional(Map<String, Object> node) {
        node.remove("required");
        for (String key : List.of(PROPERTIES, DEFS, "patternProperties")) {
            Map<String, Object> properties = map(node.get(key));
            if (properties != null) {
                properties.values().forEach(value -> visit(value, SchemaDocument::optional));
            }
        }
        for (String key : List.of("items", "additionalProperties", "anyOf", "oneOf", "allOf")) {
            visit(node.get(key), SchemaDocument::optional);
        }
    }

    /** Wraps the current output object schema inside the standard {@code ApiResult.v1} envelope schema. */
    void wrapAsApiResult() {
        Object schemaUri = root.remove("$schema");
        Object defs = root.remove(DEFS);
        Map<String, Object> dataSchema = copy(root);
        dataSchema.put("type", List.of("object", "null"));
        dataSchema.putIfAbsent(DESCRIPTION, "Result payload when ok is true, or null when ok is false.");

        Map<String, Object> issueProps = new LinkedHashMap<>();
        issueProps.put("path", Map.of("type", "string", DESCRIPTION, "Field or argument path of the issue."));
        issueProps.put("reason", Map.of("type", "string", DESCRIPTION, "Explanation of why the value was refused."));
        issueProps.put("allowed", Map.of("type", "array", "items", Map.of("type", "string"),
                DESCRIPTION, "Allowed values when the field comes from a closed set."));
        Map<String, Object> issueSchema = new LinkedHashMap<>();
        issueSchema.put("type", "object");
        issueSchema.put("additionalProperties", Boolean.FALSE);
        issueSchema.put(PROPERTIES, issueProps);

        Map<String, Object> errorProps = new LinkedHashMap<>();
        errorProps.put("code", Map.of("type", "string", DESCRIPTION, "Machine-readable error code."));
        errorProps.put("message", Map.of("type", "string", DESCRIPTION, "Human-readable error message."));
        errorProps.put("details", Map.of("type", "object", DESCRIPTION, "Structured diagnostic details by key."));
        errorProps.put("issues", Map.of("type", "array", "items", issueSchema,
                DESCRIPTION, "Field validation issues when code is VALIDATION_FAILED."));
        Map<String, Object> errorSchema = new LinkedHashMap<>();
        errorSchema.put("type", List.of("object", "null"));
        errorSchema.put("additionalProperties", Boolean.FALSE);
        errorSchema.put(DESCRIPTION, "Structured error details when ok is false, or null when ok is true.");
        errorSchema.put(PROPERTIES, errorProps);

        Map<String, Object> envelopeProps = new LinkedHashMap<>();
        envelopeProps.put("schemaVersion", Map.of("type", "string",
                DESCRIPTION, "Envelope contract schema version (1.0.0)."));
        envelopeProps.put("ok", Map.of("type", "boolean",
                DESCRIPTION, "True when the call succeeded; false when error describes the failure."));
        envelopeProps.put("data", dataSchema);
        envelopeProps.put("error", errorSchema);

        root.clear();
        if (schemaUri != null) {
            root.put("$schema", schemaUri);
        }
        if (defs != null) {
            root.put(DEFS, defs);
        }
        root.put("type", "object");
        root.put("additionalProperties", Boolean.FALSE);
        root.put("required", List.of("schemaVersion", "ok", "data", "error"));
        root.put(PROPERTIES, envelopeProps);
    }

    private Optional<Map<String, Object>> node(String path) {
        Map<String, Object> current = root;
        for (String segment : StringUtils.delimitedListToStringArray(path, ".")) {
            boolean item = segment.endsWith("[]");
            String name = item ? segment.substring(0, segment.length() - 2) : segment;
            Map<String, Object> properties = map(resolve(current).get(PROPERTIES));
            Map<String, Object> next = map(properties == null ? null : properties.get(name));
            if (next == null) {
                return Optional.empty();
            }
            current = item ? map(resolve(next).get("items")) : next;
            if (current == null) {
                return Optional.empty();
            }
        }
        return Optional.of(resolve(current));
    }

    /** The definition a {@code $ref} points to, or the node itself. */
    private Map<String, Object> resolve(Map<String, Object> node) {
        Object ref = node.get("$ref");
        if (ref instanceof String text && text.startsWith("#/" + DEFS + "/")) {
            Map<String, Object> defs = map(root.get(DEFS));
            Map<String, Object> target = defs == null ? null : map(defs.get(text.substring(("#/" + DEFS + "/").length())));
            return target == null ? node : target;
        }
        return node;
    }

    private static void close(Map<String, Object> node) {
        // an object without listed properties is a map of free keys (a Map argument); it stays open
        if ("object".equals(node.get("type")) && node.containsKey(PROPERTIES)
                && !node.containsKey("additionalProperties")) {
            node.put("additionalProperties", Boolean.FALSE);
        }
        for (Object value : List.copyOf(node.values())) {
            visit(value, SchemaDocument::close);
        }
    }

    private static void rewrite(Map<String, Object> node, UnaryOperator<String> resolve) {
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            if (DESCRIPTION.equals(entry.getKey()) && entry.getValue() instanceof String text) {
                entry.setValue(resolve.apply(text));
            } else {
                visit(entry.getValue(), child -> rewrite(child, resolve));
            }
        }
    }

    private static void nullable(Map<String, Object> node) {
        Map<String, Object> properties = map(node.get(PROPERTIES));
        if (properties != null) {
            properties.values().forEach(property -> {
                Map<String, Object> schema = map(property);
                if (schema != null && schema.get("type") instanceof String type && !"null".equals(type)) {
                    schema.put("type", List.of(type, "null"));
                }
            });
        }
        for (Object value : List.copyOf(node.values())) {
            visit(value, SchemaDocument::nullable);
        }
    }

    private static void visit(Object value, java.util.function.Consumer<Map<String, Object>> action) {
        Map<String, Object> map = map(value);
        if (map != null) {
            action.accept(map);
        } else if (value instanceof List<?> list) {
            list.forEach(element -> visit(element, action));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static Map<String, Object> copy(Map<String, Object> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        source.forEach((key, value) -> out.put(key, copyValue(value)));
        return out;
    }

    private static Object copyValue(Object value) {
        Map<String, Object> map = map(value);
        if (map != null) {
            return copy(map);
        }
        if (value instanceof List<?> list) {
            return new ArrayList<>(list.stream().map(SchemaDocument::copyValue).toList());
        }
        return value;
    }
}
