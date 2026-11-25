// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.application.write.FieldsInput;
import org.pgledger.domain.value.Patch;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * SVC-DIG-02: JSON binding of {@link FieldsInput} (three-state patches: set, empty-to-clear, absent).
 */
class FieldsInputTest {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();

    @Test
    @DisplayName("SVC-DIG-02: text is set, empty text clears, JSON null and omitted keys are absent")
    void threeStates() {
        FieldsInput fields = MAPPER.readValue("{\"title\":\"x\",\"owner_actor_id\":null,\"root_cause\":\"\"}",
                FieldsInput.class);

        assertThat(fields.title()).isEqualTo(Patch.set("x"));
        assertThat(fields.ownerActorId()).isEqualTo(Patch.absent());
        assertThat(fields.ownerActorId().mentioned()).isFalse();
        assertThat(fields.rootCause()).isEqualTo(Patch.set(""));
        assertThat(fields.rootCause().mentioned()).isTrue();
        assertThat(fields.projectId()).isEqualTo(Patch.absent());
        assertThat(fields.symptom()).isEqualTo(Patch.absent());
        assertThat(fields.criterionText()).isEqualTo(Patch.absent());
    }

    @Test
    @DisplayName("SVC-DIG-02: empty object leaves every key absent; JSON null equals omitted")
    void emptyObject() {
        FieldsInput fields = MAPPER.readValue("{}", FieldsInput.class);

        assertThat(fields).isEqualTo(new FieldsInput(null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null));
        assertThat(fields.title().mentioned()).isFalse();
        assertThat(MAPPER.readValue("{\"title\":null}", FieldsInput.class)).isEqualTo(fields);
        assertThat(MAPPER.readValue("{\"title\":\"\"}", FieldsInput.class).title()).isNotEqualTo(fields.title());
    }

    @Test
    @DisplayName("SVC-DIG-02: map with null values converts identically to JSON text")
    void aMapIsConvertedLikeItsText() {
        Map<String, Object> written = new LinkedHashMap<>();
        written.put("project_id", "p1");
        written.put("success_condition", null);
        written.put("origin_quote", "the words");

        FieldsInput fields = MAPPER.convertValue(written, FieldsInput.class);

        assertThat(fields.projectId()).isEqualTo(Patch.set("p1"));
        assertThat(fields.successCondition()).isEqualTo(Patch.absent());
        assertThat(fields.originQuote()).isEqualTo(Patch.set("the words"));
        assertThat(fields.originRef()).isEqualTo(Patch.absent());
        assertThat(fields).isEqualTo(MAPPER.readValue(
                "{\"project_id\":\"p1\",\"success_condition\":null,\"origin_quote\":\"the words\"}",
                FieldsInput.class));
    }

    @Test
    @DisplayName("SVC-DIG-02: unknown keys and non-scalar values are refused")
    void unknownKeyAndWrongType() {
        assertThatThrownBy(() -> MAPPER.readValue("{\"titel\":\"x\"}", FieldsInput.class))
                .isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> MAPPER.readValue("{\"title\":{\"a\":1}}", FieldsInput.class))
                .isInstanceOf(JacksonException.class);
    }

    @Test
    @DisplayName("SVC-DIG-02: null passed to constructor component means absent")
    void nullInCodeMeansAbsent() {
        FieldsInput fields = new FieldsInput(Patch.set("p"), null, null, null, null, null, null, null, null, null,
                Patch.set(null), null, null, null, null, null, null, null, null);

        assertThat(fields.title()).isEqualTo(Patch.absent());
        assertThat(fields.projectId().mentioned()).isTrue();
        assertThat(fields.ownerActorId().mentioned()).isTrue();
    }
}
