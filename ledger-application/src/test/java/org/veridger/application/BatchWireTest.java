// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.veridger.application.support.Fields;
import org.veridger.application.write.BatchOpInput;
import org.veridger.domain.model.BatchOpKind;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/** DOM-025 (application side): wire records of a batch carry the exact keys of {@link BatchOpKind}. */
class BatchWireTest {

    private static final JsonMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();

    private static BatchOpInput filled(BatchOpKind kind) {
        return switch (kind) {
            case SAVE -> new BatchOpInput.Save("issue", Fields.of("title", "x"), "reason", "id", 0L, "2.0.0");
            case TRANSITION -> new BatchOpInput.Transition("issue", "id", 1L, "OPEN", "reason", "uri", "sha",
                    "target", "attachment");
            case RECORD_DELIVERABLE -> new BatchOpInput.RecordDeliverable("issue", "id", "code_change", "ref", "reason",
                    "loc", "summary", "check", "passed", "time", true, "attachment");
            case RECORD_CASE -> new BatchOpInput.RecordCase("issue", "seen", "reason", "evidence", "quote", "time",
                    "attachment");
            case LINK -> new BatchOpInput.Link("kind", "a", "b", "reason", false);
            case CORRECT -> new BatchOpInput.Correct(1L, "reason", "attachment");
            case PROJECT_VERSION -> new BatchOpInput.ProjectVersion("project", "new", "reason", false, "old",
                    "description", List.of("P-I-0001"), List.of("P-I-0002"));
        };
    }

    @ParameterizedTest
    @EnumSource(BatchOpKind.class)
    @DisplayName("an operation record has the keys of its BatchOpKind and the op discriminator")
    void wireKeysEqualTheDomainKeys(BatchOpKind kind) {
        Map<?, ?> written = JSON.readValue(JSON.writeValueAsString(filled(kind)), Map.class);
        assertThat(written.keySet()).isEqualTo(kind.allowedKeys());
        assertThat(written.get("op")).isEqualTo(kind.token());
    }
}
