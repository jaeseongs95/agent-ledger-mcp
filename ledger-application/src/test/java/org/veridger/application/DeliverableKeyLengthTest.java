// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.veridger.application.history.HistoryKey;
import org.veridger.application.support.LedgerServiceTest;
import org.veridger.application.support.Scenario;
import org.veridger.application.write.RecordDeliverableCommand;
import org.veridger.application.write.RecordDeliverableService;
import org.veridger.application.write.WriteResult.RecordDeliverableResult;
import org.veridger.domain.model.HistoryEntry;
import org.veridger.domain.rule.DeliverableTarget;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.rule.HistoryOp;
import org.veridger.domain.value.TextRule;
import org.veridger.domain.value.Tokens;

/**
 * R9-D0055: a deliverable reference of the longest length that the tool accepts (300 characters) is recorded: the
 * composite id {@code <target>:<id>@<kind>:<ref>} of its history rows is longer than the 300 characters of
 * {@code history.entity_id}, so it is the shortened form of {@link HistoryKey#deliverableEntityId}, and the same key
 * finds the rows of the deliverable again (an amendment of its check facts writes to the same key).
 */
@LedgerServiceTest
class DeliverableKeyLengthTest {

    private static RecordDeliverableCommand command(String entity, String id, String ref, String checkRef,
            String checkResult) {
        return new RecordDeliverableCommand(Scenario.uuid(), entity, id, "code_change", ref, Scenario.REASON,
                "repo/e2e", "Deliverable summary", checkRef, checkResult, null, Boolean.FALSE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"issue", "spec", "task"})
    @DisplayName("R9-D0055: a deliverable_ref of 300 characters is recorded, its history rows share one key of at most 300 characters, and an amendment finds them")
    void aReferenceOfTheLongestLengthIsRecorded(String entity, Scenario s) {
        DeliverableTarget target = Tokens.parse("target_entity", entity, DeliverableTarget.values());
        String id = switch (target) {
            case ISSUE -> s.newIssue("i").id();
            case SPEC -> s.newSpec("s").id();
            case TASK -> s.newTask("t").id();
        };
        String ref = "x".repeat(TextRule.DELIVERABLE_REF.max());
        var service = s.bean(RecordDeliverableService.class);
        RecordDeliverableResult recorded = service.recordDeliverable(Scenario.AUTHOR,
                command(entity, id, ref, null, null));
        RecordDeliverableResult amended = service.recordDeliverable(Scenario.AUTHOR,
                command(entity, id, ref, "ci/1", "passed"));

        String key = HistoryKey.deliverableEntityId(entity, id, "code_change", ref);
        assertThat(key.codePointCount(0, key.length())).isEqualTo(HistoryEntry.MAX_ENTITY_ID_LENGTH);
        List<HistoryEntry> rows = s.history(HistoryEntity.DELIVERABLE, key);
        assertThat(rows).extracting(HistoryEntry::id, HistoryEntry::op, HistoryEntry::entityId).containsExactly(
                tuple(recorded.historyId(), HistoryOp.CREATE, key),
                tuple(amended.historyId(), HistoryOp.EDIT, key));
        assertThat(s.changes(recorded.historyId()).entries().get("deliverable_ref").after())
                .as("the whole reference is in the changes of the row").isEqualTo(ref);
    }
}
