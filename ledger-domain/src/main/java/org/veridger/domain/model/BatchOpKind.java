// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.domain.model;

import java.util.HashSet;
import java.util.Set;
import org.veridger.domain.value.Token;

/**
 * The author operations a batch can hold. The key sets are the external contract that
 * the JSON boundary checks: required keys must be present, unknown keys are rejected.
 */
public enum BatchOpKind implements Token {
    SAVE("save", Set.of("entity", "fields", "reason"), Set.of("id", "expected_version", "project_version")),
    TRANSITION("transition", Set.of("entity", "id", "expected_version", "to", "reason"),
            Set.of("evidence_uri", "evidence_sha256", "merged_into", "attachment_id")),
    RECORD_DELIVERABLE("record_deliverable",
            Set.of("target_entity", "target_id", "kind", "deliverable_ref", "summary", "reason"),
            Set.of("location", "check_ref", "check_result", "checked_at", "mark_done", "attachment_id")),
    RECORD_CASE("record_case", Set.of("issue_id", "observation", "reason"),
            Set.of("evidence", "source_quote", "observed_at", "attachment_id")),
    LINK("link", Set.of("kind", "a", "b", "reason"), Set.of("remove")),
    CORRECT("correct", Set.of("history_id", "reason"), Set.of("attachment_id")),
    PROJECT_VERSION("project_version", Set.of("project_id", "name", "reason"),
            Set.of("create", "original_name", "description", "link_issue_ids", "unlink_issue_ids",
                    "link_items", "unlink_items"));

    /** The most operations one batch holds (a batch has 1 to this many). */
    public static final int MAX_OPS_PER_BATCH = 200;

    private final String token;
    private final Set<String> required;
    private final Set<String> optional;

    BatchOpKind(String token, Set<String> required, Set<String> optional) {
        this.token = token;
        this.required = required;
        this.optional = optional;
    }

    @Override
    public String token() {
        return token;
    }

    public Set<String> requiredKeys() {
        return required;
    }

    /** Every key an operation of this kind may carry, including {@code op}. The key of an operation is derived from the key of the batch. */
    public Set<String> allowedKeys() {
        Set<String> all = new HashSet<>(required);
        all.addAll(optional);
        all.add("op");
        return Set.copyOf(all);
    }
}
