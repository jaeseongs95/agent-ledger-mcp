// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.agentledger.application.admin.GrantService;
import org.agentledger.application.admin.GrantService.GrantCommand;
import org.agentledger.application.admin.ProjectService;
import org.agentledger.application.admin.ProjectService.ProjectCommand;
import org.agentledger.application.support.Fields;
import org.agentledger.application.support.LedgerServiceTest;
import org.agentledger.application.support.Scenario;
import org.agentledger.application.write.BatchOpInput;
import org.agentledger.application.write.BatchService;
import org.agentledger.application.write.SaveCommand;
import org.agentledger.application.write.SaveService;
import org.agentledger.domain.value.CanonicalPayload;
import org.agentledger.domain.value.OperationKey;
import org.agentledger.testing.TestOwners;

/**
 * SVC-DIG-01: receipt digest determinism for 2.0 saves (issue, spec, task, criterion, edits, null/blank clearing, and
 * batch-derived saves). Task digests remain pinned to the historical hex strings; issue, spec, and criterion digests
 * verify exact canonical encoding independent of map key order or blank/null spelling.
 */
@LedgerServiceTest
class SaveDigestTest {

    private static final String PROJECT = "digpin";
    private static final String CODE = "DIGP";

    private static final Map<String, String> PINNED = new LinkedHashMap<>();

    private static final String CREATE_ISSUE = "00000000-0000-4000-8000-0000000000a1";
    private static final String CREATE_SPEC = "00000000-0000-4000-8000-0000000000a9";
    private static final String CREATE_TASK = "00000000-0000-4000-8000-0000000000a2";
    private static final String CREATE_CRITERION = "00000000-0000-4000-8000-0000000000a3";
    private static final String EDIT_ISSUE = "00000000-0000-4000-8000-0000000000a4";
    private static final String CLEAR_OWNER_NULL = "00000000-0000-4000-8000-0000000000a5";
    private static final String SET_OWNER = "00000000-0000-4000-8000-0000000000aa";
    private static final String CLEAR_OWNER_BLANK = "00000000-0000-4000-8000-0000000000a6";
    private static final String EDIT_TASK_PROJECT = "00000000-0000-4000-8000-0000000000a7";
    private static final String CLEAR_ORIGIN = "00000000-0000-4000-8000-0000000000a8";
    private static final String BATCH_KEY = "00000000-0000-4000-8000-0000000000ff";
    private static final String BATCH_SAVE = OperationKey.parse(BATCH_KEY).derive(0).toString();

    private static String expectedSave(String entity, String id, long v, Map<String, String> fields) {
        CanonicalPayload.Fields f = CanonicalPayload.Fields.empty();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            f = f.text(e.getKey(), e.getValue());
        }
        return CanonicalPayload.digest("SAVE", CanonicalPayload.Fields.empty()
                .text("entity", entity)
                .text("id", id)
                .text("v", Long.toString(v))
                .with("fields", f)
                .text("reason", Scenario.REASON)).hex();
    }

    static {
        PINNED.put(CREATE_ISSUE, expectedSave("issue", CODE + "-I-0001", 0L,
                fields("project_id", PROJECT, "title", "digest issue", "category", "ARC", "severity", "P2", "symptom",
                        "what happens", "root_cause", "why", "owner_actor_id", Scenario.AUTHOR)));
        PINNED.put(CREATE_SPEC, expectedSave("spec", CODE + "-S-0001", 0L,
                fields("project_id", PROJECT, "title", "digest spec", "summary", "the summary",
                        "acceptance_criteria", "the acceptance", "priority", "P1", "owner_actor_id", Scenario.AUTHOR)));
        PINNED.put(CREATE_TASK, "6f915f51f1678dea88c8011c116466de8cf1550c9623978ce8642cce22dc836a");
        PINNED.put(CREATE_CRITERION, expectedSave("criterion", CODE + "-C-1", 0L,
                fields("project_id", PROJECT, "milestone_name", "r1", "goal_label", "goal", "code", "c1",
                        "criterion_text", "the criterion")));
        PINNED.put(EDIT_ISSUE, expectedSave("issue", CODE + "-I-0001", 1L,
                fields("title", "edited title", "symptom", "edited symptom")));
        PINNED.put(CLEAR_OWNER_NULL, expectedSave("issue", CODE + "-I-0001", 2L,
                fields("owner_actor_id", "\u0000")));
        PINNED.put(SET_OWNER, expectedSave("issue", CODE + "-I-0001", 3L,
                fields("owner_actor_id", Scenario.AUTHOR)));
        PINNED.put(CLEAR_OWNER_BLANK, expectedSave("issue", CODE + "-I-0001", 4L,
                fields("owner_actor_id", "\u0000")));
        PINNED.put(EDIT_TASK_PROJECT, "1cafddd218d6052c3ff2c3fb75e4d5cf879675cdd4a2382c6d778afab4f3198e");
        PINNED.put(CLEAR_ORIGIN, "a136a3f212243b800c4540f80bc11b7f7ef3d92fd61ead116f4fbd57b9e8691c");
        PINNED.put(BATCH_SAVE, expectedSave("issue", CODE + "-I-0002", 0L,
                fields("project_id", PROJECT, "title", "batch issue", "category", "ARC", "severity", "P2",
                        "symptom", "b", "root_cause", "c")));
    }

    private static Map<String, String> fields(String... keysAndValues) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            fields.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return fields;
    }

    private static SaveCommand save(String operationKey, String entity, String id, Long version,
            Map<String, String> fields) {
        return new SaveCommand(operationKey, entity, id, version, Fields.of(fields), Scenario.REASON);
    }

    @Test
    @DisplayName("SVC-DIG-01: the receipt digest of a save matches its canonical specification across all entities")
    void theDigestOfASaveIsPinned(Scenario s) {
        s.bean(ProjectService.class).add(new ProjectCommand(PROJECT, CODE, "digest pin"), TestOwners.OWNER);
        s.bean(GrantService.class).grant(new GrantCommand(Scenario.AUTHOR, PROJECT,
                List.of("issue", "spec", "task"), null, null), TestOwners.OWNER);
        SaveService saves = s.bean(SaveService.class);
        Map<String, String> owner = fields("owner_actor_id", null);

        saves.save(Scenario.AUTHOR, save(CREATE_ISSUE, "issue", CODE + "-I-0001", 0L,
                fields("project_id", PROJECT, "title", "digest issue", "category", "ARC", "severity", "P2", "symptom",
                        "what happens", "root_cause", "why", "owner_actor_id", Scenario.AUTHOR)));
        saves.save(Scenario.AUTHOR, save(CREATE_SPEC, "spec", CODE + "-S-0001", 0L,
                fields("project_id", PROJECT, "title", "digest spec", "summary", "the summary",
                        "acceptance_criteria", "the acceptance", "priority", "P1", "owner_actor_id", Scenario.AUTHOR)));
        saves.save(Scenario.AUTHOR, save(CREATE_TASK, "task", CODE + "-T-0001", 0L,
                fields("project_id", PROJECT, "title", "digest task", "purpose", "the purpose", "success_condition",
                        "the success", "owner_actor_id", Scenario.AUTHOR, "origin_ref", "ref-1", "origin_quote",
                        "the words")));
        saves.save(Scenario.AUTHOR, save(CREATE_CRITERION, "criterion", CODE + "-C-1", 0L,
                fields("project_id", PROJECT, "milestone_name", "r1", "goal_label", "goal", "code", "c1",
                        "criterion_text", "the criterion")));
        saves.save(Scenario.AUTHOR, save(EDIT_ISSUE, "issue", CODE + "-I-0001", 1L,
                fields("title", "edited title", "symptom", "edited symptom")));
        saves.save(Scenario.AUTHOR, save(CLEAR_OWNER_NULL, "issue", CODE + "-I-0001", 2L, owner));
        saves.save(Scenario.AUTHOR, save(SET_OWNER, "issue", CODE + "-I-0001", 3L,
                fields("owner_actor_id", Scenario.AUTHOR)));
        saves.save(Scenario.AUTHOR, save(CLEAR_OWNER_BLANK, "issue", CODE + "-I-0001", 4L,
                fields("owner_actor_id", "   ")));
        saves.save(Scenario.AUTHOR, save(EDIT_TASK_PROJECT, "task", CODE + "-T-0001", 1L,
                fields("project_id", PROJECT, "purpose", "edited purpose")));
        saves.save(Scenario.AUTHOR, save(CLEAR_ORIGIN, "task", CODE + "-T-0001", 2L, fields("origin_ref", null)));
        s.bean(BatchService.class).batch(Scenario.AUTHOR, BATCH_KEY,
                List.of(new BatchOpInput.Save("issue", Fields.of(fields("project_id", PROJECT, "title", "batch issue",
                        "category", "ARC", "severity", "P2", "symptom", "b", "root_cause", "c")), Scenario.REASON,
                        CODE + "-I-0002", 0L)));

        SoftAssertions digests = new SoftAssertions();
        PINNED.forEach((operationKey, expected) -> digests.assertThat(digest(s, operationKey)).as(operationKey)
                .isEqualTo(expected));
        digests.assertAll();
        assertThat(PINNED).hasSize(11);
    }

    private static String digest(Scenario s, String operationKey) {
        return s.ctx().jdbc().sql("SELECT payload_sha256 FROM request_receipt WHERE operation_key = :id")
                .param("id", operationKey).query(String.class).single();
    }
}
