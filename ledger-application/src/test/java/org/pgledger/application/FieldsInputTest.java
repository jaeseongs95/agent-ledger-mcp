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
 * SVC-DIG-02: the fields of a save read by the JSON mapper. The mapper is made like the one of the tool binding (an
 * unknown key is refused, a text is not coerced), because that is the one that reads them in production; no database.
 */
class FieldsInputTest {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();

    @Test
    @DisplayName("SVC-DIG-02 (OPK-021, changed: a JSON null used to be set to nothing and cleared the field, now it counts as not given): a text is set, an empty text is set (the one way to clear), a JSON null and a key that is left out are absent")
    void threeStates() {
        FieldsInput fields = MAPPER.readValue("{\"title\":\"x\",\"owner_actor_id\":null,\"root_cause\":\"\"}",
                FieldsInput.class);

        assertThat(fields.title()).isEqualTo(Patch.set("x"));
        assertThat(fields.ownerActorId()).isEqualTo(Patch.absent());
        assertThat(fields.ownerActorId().mentioned()).isFalse();
        assertThat(fields.rootCause()).isEqualTo(Patch.set(""));
        assertThat(fields.rootCause().mentioned()).isTrue();
        assertThat(fields.projectId()).isEqualTo(Patch.absent());
        assertThat(fields.behavior()).isEqualTo(Patch.absent());
        assertThat(fields.conditionText()).isEqualTo(Patch.absent());
    }

    @Test
    @DisplayName("SVC-DIG-02 (OPK-021, changed: the JSON null and a missing key used to differ, now they are the same): an empty object leaves every key absent; a JSON null is absent too, only an empty text differs from a missing key")
    void emptyObject() {
        FieldsInput fields = MAPPER.readValue("{}", FieldsInput.class);

        assertThat(fields).isEqualTo(new FieldsInput(null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null));
        assertThat(fields.title().mentioned()).isFalse();
        assertThat(MAPPER.readValue("{\"title\":null}", FieldsInput.class)).isEqualTo(fields);
        assertThat(MAPPER.readValue("{\"title\":\"\"}", FieldsInput.class).title()).isNotEqualTo(fields.title());
    }

    @Test
    @DisplayName("SVC-DIG-02 (OPK-021, changed: a null value is absent now, not a clearing patch): the object a tool call carries "
            + "(a map with null values, converted) binds the same way as its text, and the keys keep their wire names")
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
                "{\"project_id\":\"p1\",\"success_condition\":null,\"origin_quote\":\"the words\"}", FieldsInput.class));
    }

    @Test
    @DisplayName("SVC-DIG-02: an unknown key and an object where a text is expected are refused")
    void unknownKeyAndWrongType() {
        assertThatThrownBy(() -> MAPPER.readValue("{\"titel\":\"x\"}", FieldsInput.class))
                .isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> MAPPER.readValue("{\"title\":{\"a\":1}}", FieldsInput.class))
                .isInstanceOf(JacksonException.class);
    }

    @Test
    @DisplayName("SVC-DIG-02: a record made in code and given null for a key means the key is left out")
    void nullInCodeMeansAbsent() {
        FieldsInput fields = new FieldsInput(Patch.set("p"), null, null, null, null, null, Patch.set(null), null, null,
                null, null, null, null, null, null);

        assertThat(fields.title()).isEqualTo(Patch.absent());
        assertThat(fields.projectId().mentioned()).isTrue();
        assertThat(fields.ownerActorId().mentioned()).isTrue();
    }
}
