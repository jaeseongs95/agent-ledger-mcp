// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Direct unit tests for {@link SchemaDocument} covering deep copies, {@code $ref} resolution, array item paths,
 * {@code additionalProperties} closing, enum restriction, description rewriting, nullability, and {@code ApiResult.v1}
 * envelope wrapping.
 */
class SchemaDocumentUnitTest {

    @Test
    @DisplayName("MCP-SCH-01 SchemaDocument handles $ref, array items, missing paths, closeObjects, allowNull, and wrapAsApiResult")
    void exercisesAllSchemaDocumentOperations() {
        Map<String, Object> itemSchema = new LinkedHashMap<>();
        itemSchema.put("type", "object");
        itemSchema.put("properties", new LinkedHashMap<>(Map.of(
                "code", new LinkedHashMap<>(Map.of("type", "string", "description", "key.code")),
                "alreadyNull", new LinkedHashMap<>(Map.of("type", "null")))));

        Map<String, Object> defs = new LinkedHashMap<>();
        defs.put("Entry", itemSchema);

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("items", new LinkedHashMap<>(Map.of("type", "array", "items", Map.of("$ref", "#/$defs/Entry"))));
        props.put("brokenRef", new LinkedHashMap<>(Map.of("$ref", "#/$defs/Missing")));
        props.put("externalRef", new LinkedHashMap<>(Map.of("$ref", "https://example.com/other")));
        props.put("openMap", new LinkedHashMap<>(Map.of("type", "object")));
        props.put("alreadyClosed", new LinkedHashMap<>(Map.of("type", "object", "properties", Map.of(),
                "additionalProperties", Boolean.TRUE)));
        props.put("notArray", new LinkedHashMap<>(Map.of("type", "string")));
        props.put("union", List.of(new LinkedHashMap<>(Map.of("description", "key.union"))));

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        raw.put("$defs", defs);
        raw.put("type", "object");
        raw.put("properties", props);

        SchemaDocument doc = SchemaDocument.copyOf(raw);
        doc.closeObjects();
        assertThat(doc.toSdk()).containsEntry("additionalProperties", Boolean.FALSE);
        assertThat(raw).doesNotContainKey("additionalProperties");

        doc.restrict("items[].code", List.of());
        doc.restrict("items[].code", List.of("A", "B"));
        assertThat(doc.description("items[].code")).contains("key.code");
        doc.describe("items[].code", "updated.desc");
        assertThat(doc.description("items[].code")).contains("updated.desc");

        // Missing paths, broken $ref, non-array item path, and non-string description
        assertThat(doc.description("noSuch.field")).isEmpty();
        assertThat(doc.description("notArray[].x")).isEmpty();
        assertThat(doc.description("brokenRef.x")).isEmpty();
        assertThat(doc.description("externalRef.x")).isEmpty();

        doc.rewriteDescriptions(key -> "resolved:" + key);
        assertThat(doc.description("items[].code")).contains("resolved:updated.desc");

        doc.allowNullProperties();
        doc.wrapAsApiResult();
        assertThat(doc.toSdk()).containsKey("$schema").containsKey("$defs");
        assertThat(doc.toSdk().get("required")).isEqualTo(List.of("schemaVersion", "ok", "data", "error"));

        // wrapAsApiResult without $schema or $defs
        SchemaDocument minimal = SchemaDocument.copyOf(Map.of("type", "object", "properties", Map.of()));
        minimal.wrapAsApiResult();
        assertThat(minimal.toSdk()).doesNotContainKey("$schema").doesNotContainKey("$defs");
    }
}
