// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application.support;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.pgledger.application.write.FieldsInput;
import org.pgledger.domain.value.Patch;

/**
 * Builds a {@link FieldsInput} from a map of wire keys for tests: present keys become {@link Patch#set}, absent keys
 * become {@code null} ({@link Patch#absent}).
 */
public final class Fields {

    private static final Set<String> KEYS = Set.of(
            "project_id", "parent_id", "title", "category", "severity", "symptom", "root_cause",
            "summary", "acceptance_criteria", "priority", "owner_actor_id", "purpose", "success_condition",
            "origin_ref", "origin_quote", "milestone_name", "goal_label", "code", "criterion_text");

    private Fields() {
    }

    public static FieldsInput of(Map<String, String> raw) {
        for (String key : raw.keySet()) {
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException("not a key of FieldsInput: " + key);
            }
        }
        return new FieldsInput(
                patch(raw, "project_id"),
                patch(raw, "parent_id"),
                patch(raw, "title"),
                patch(raw, "category"),
                patch(raw, "severity"),
                patch(raw, "symptom"),
                patch(raw, "root_cause"),
                patch(raw, "summary"),
                patch(raw, "acceptance_criteria"),
                patch(raw, "priority"),
                patch(raw, "owner_actor_id"),
                patch(raw, "purpose"),
                patch(raw, "success_condition"),
                patch(raw, "origin_ref"),
                patch(raw, "origin_quote"),
                patch(raw, "milestone_name"),
                patch(raw, "goal_label"),
                patch(raw, "code"),
                patch(raw, "criterion_text"));
    }

    public static FieldsInput of(String key, String value) {
        Map<String, String> one = new HashMap<>();
        one.put(key, value);
        return of(one);
    }

    private static Patch<String> patch(Map<String, String> raw, String key) {
        return raw.containsKey(key) ? Patch.set(raw.get(key)) : null;
    }
}
