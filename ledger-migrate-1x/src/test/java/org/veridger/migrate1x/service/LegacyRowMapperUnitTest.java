// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.migrate1x.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.domain.error.ValidationReason;
import org.veridger.migrate1x.database.ColumnRule;
import org.veridger.migrate1x.database.LegacyColumn;
import org.veridger.migrate1x.database.LegacyRow;
import org.veridger.migrate1x.database.LegacyTable;

/**
 * Exhaustive unit tests for {@link LegacyRowMapper} exercising all table mappings, actor-prefix normalization,
 * microsecond truncation, violation collection, and column metadata checks without database I/O.
 */
class LegacyRowMapperUnitTest {

    private final LegacyRowMapper mapper = new LegacyRowMapper();

    private static Map<String, ColumnRule> permissiveRules(LegacyTable table) {
        Map<String, ColumnRule> rules = new LinkedHashMap<>();
        for (LegacyColumn col : table.targetColumns()) {
            rules.put(col.name(), new ColumnRule(true, 0));
        }
        return rules;
    }

    private static LegacyRow row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return new LegacyRow(m);
    }

    @Test
    @DisplayName("MIG-MAP-01 maps violations across PROJECT, COMMON_CODE, TASK, DEFECT, DEFECT_FIX, and DEFECT_RELATION")
    void mapsProjectCodeTaskDefectFixAndRelationViolations() {
        ImportCounters counters = new ImportCounters();

        // PROJECT: invalid id, invalid code, missing required title, maxChars exceeded
        Map<String, ColumnRule> projRules = permissiveRules(LegacyTable.PROJECT);
        projRules.put("title", new ColumnRule(false, 5));
        LegacyRowMapper.Mapped projBad = mapper.map(LegacyTable.PROJECT,
                row("id", "BAD ID!", "code", "bad!", "title", "", "created_by", "agent:codex:sess1"),
                projRules, counters);
        assertThat(projBad.row().text("created_by")).isEqualTo("codex:sess1");
        assertThat(projBad.violations()).extracting(LegacyViolation::column)
                .contains("id", "code", "title");

        LegacyRowMapper.Mapped projTooLong = mapper.map(LegacyTable.PROJECT,
                row("id", "proj_a", "code", "PRJ", "title", "123456", "created_by", "agent:"),
                projRules, counters);
        assertThat(projTooLong.row().text("created_by")).isEqualTo("agent:");
        assertThat(projTooLong.violations()).extracting(LegacyViolation::reason)
                .containsExactly(ValidationReason.MUST_BE_AT_MOST_CHARS);

        // Missing column rule throws IllegalStateException
        assertThatThrownBy(() -> mapper.map(LegacyTable.PROJECT,
                row("id", "proj_a", "code", "PRJ", "title", "ok", "created_by", "owner:admin"),
                Map.of(), counters))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no column");

        // COMMON_CODE: unknown group, inactive flag, bad code
        LegacyRowMapper.Mapped codeBad = mapper.map(LegacyTable.COMMON_CODE,
                row("code_group", "NO_SUCH_GROUP", "code", "bad code", "name_ko", "k", "name_en", "e",
                        "description_ko", null, "description_en", null, "sort_order", 1L, "active", false,
                        "created_by", "owner:admin"),
                permissiveRules(LegacyTable.COMMON_CODE), counters);
        assertThat(codeBad.row().text("state")).isEqualTo("INACTIVE");
        assertThat(codeBad.violations()).extracting(LegacyViolation::column).contains("code_group", "code");

        // TASK: invalid id, unknown state, null id, microsecond truncation
        Instant nanos = Instant.parse("2026-11-27T01:02:03.123456789Z");
        LegacyRowMapper.Mapped taskBad = mapper.map(LegacyTable.TASK,
                row("id", "bad task id", "project_id", "p1", "title", "t", "purpose", null, "success_condition", null,
                        "state", "NO_STATE", "owner_actor_id", "agent:claude:abc", "origin_ref", null,
                        "origin_quote", null, "version", 1L, "created_at", nanos, "updated_at", nanos,
                        "created_by", "owner:admin"),
                permissiveRules(LegacyTable.TASK), counters);
        assertThat(taskBad.row().instant("created_at")).isEqualTo(Instant.parse("2026-11-27T01:02:03.123456Z"));
        assertThat(taskBad.violations()).extracting(LegacyViolation::column).contains("id", "state");

        LegacyRowMapper.Mapped taskNullId = mapper.map(LegacyTable.TASK,
                row("id", null, "project_id", "p1", "title", "t", "purpose", null, "success_condition", null,
                        "state", "REPORTED_DONE", "owner_actor_id", null, "origin_ref", null,
                        "origin_quote", null, "version", 1L, "created_at", null, "updated_at", null,
                        "created_by", "owner:admin"),
                permissiveRules(LegacyTable.TASK), counters);
        assertThat(taskNullId.row().text("id_key")).isNull();
        assertThat(taskNullId.row().text("state")).isEqualTo("COMPLETED");

        // DEFECT: invalid id, invalid category/severity, unknown status
        LegacyRowMapper.Mapped defectBad = mapper.map(LegacyTable.DEFECT,
                row("id", "NOT-AN-ISSUE", "project_id", "p1", "title", "t", "category", "bad cat",
                        "severity", "bad sev", "status", "BOGUS", "merged_into", null, "behavior", "sym",
                        "root_cause", null, "owner_actor_id", null, "version", 1L, "created_at", null,
                        "updated_at", null, "created_by", "owner:admin"),
                permissiveRules(LegacyTable.DEFECT), counters);
        assertThat(defectBad.violations()).extracting(LegacyViolation::column)
                .contains("id", "category", "severity", "status");

        // DEFECT_FIX: missing repo, check_result without check_ref, unknown ci_conclusion, no branch, imported=true
        LegacyRowMapper.Mapped fixBad = mapper.map(LegacyTable.DEFECT_FIX,
                row("id", 1L, "defect_id", "PRJ-I-0001", "repo", "", "commit_sha", "abc", "branch", "",
                        "ci_ref", "", "ci_conclusion", "success", "ci_completed_at", null,
                        "recorded_by", "owner:admin", "recorded_at", null, "imported", true),
                permissiveRules(LegacyTable.DEFECT_FIX), counters);
        assertThat(fixBad.row().text("summary")).isEqualTo("imported 1.x commit");
        assertThat(fixBad.row().text("origin")).isEqualTo("IMPORTED");
        assertThat(fixBad.violations()).extracting(LegacyViolation::column).contains("location", "check_ref");

        LegacyRowMapper.Mapped fixUnknownConclusion = mapper.map(LegacyTable.DEFECT_FIX,
                row("id", 2L, "defect_id", "PRJ-I-0001", "repo", "org/repo", "commit_sha", "abc", "branch", "main",
                        "ci_ref", "https://ci/1", "ci_conclusion", "weird", "ci_completed_at", null,
                        "recorded_by", "owner:admin", "recorded_at", null, "imported", false),
                permissiveRules(LegacyTable.DEFECT_FIX), counters);
        assertThat(fixUnknownConclusion.row().text("summary")).isEqualTo("branch main");
        assertThat(fixUnknownConclusion.row().text("check_result")).isNull();
        assertThat(fixUnknownConclusion.violations()).extracting(LegacyViolation::column).contains("check_result");

        // DEFECT_RELATION: invalid kind
        LegacyRowMapper.Mapped relBad = mapper.map(LegacyTable.DEFECT_RELATION,
                row("a", "PRJ-I-0001", "b", "PRJ-I-0002", "kind", "bad kind", "created_by", "owner:admin"),
                permissiveRules(LegacyTable.DEFECT_RELATION), counters);
        assertThat(relBad.violations()).extracting(LegacyViolation::column).containsExactly("kind");
    }

    @Test
    @DisplayName("MIG-MAP-02 maps violations across RELEASE_CONDITION, CONDITION_LINK, SESSION_GRANT, and HISTORY")
    void mapsConditionLinkGrantAndHistoryViolations() {
        ImportCounters counters = new ImportCounters();

        // RELEASE_CONDITION: invalid state
        LegacyRowMapper.Mapped condBad = mapper.map(LegacyTable.RELEASE_CONDITION,
                row("id", "C-1", "project_id", "p1", "release", "v1", "goal_label", "g", "code", "c",
                        "condition_text", "txt", "state", "NO_SUCH", "version", 1L, "created_by", "owner:admin"),
                permissiveRules(LegacyTable.RELEASE_CONDITION), counters);
        assertThat(condBad.violations()).extracting(LegacyViolation::column).contains("state");

        // CONDITION_LINK: both null, both non-null, bad criterion_id, bad task_id, bad issue_id, valid task & valid issue
        LegacyRowMapper.Mapped clBothNull = mapper.map(LegacyTable.CONDITION_LINK,
                row("condition_id", "bad crit id", "task_id", null, "defect_id", null, "created_by", "owner:admin"),
                permissiveRules(LegacyTable.CONDITION_LINK), counters);
        assertThat(clBothNull.violations()).extracting(LegacyViolation::column).contains("task_id", "criterion_id");

        LegacyRowMapper.Mapped clBothSet = mapper.map(LegacyTable.CONDITION_LINK,
                row("condition_id", "C-1", "task_id", "T-1", "defect_id", "PRJ-I-0001", "created_by", "owner:admin"),
                permissiveRules(LegacyTable.CONDITION_LINK), counters);
        assertThat(clBothSet.row().text("link_key")).isNull();
        assertThat(clBothSet.violations()).extracting(LegacyViolation::reason)
                .contains(ValidationReason.MUST_BE_EXACTLY_ONE_OF);

        LegacyRowMapper.Mapped clBadTask = mapper.map(LegacyTable.CONDITION_LINK,
                row("condition_id", "C-1", "task_id", "bad task", "defect_id", null, "created_by", "owner:admin"),
                permissiveRules(LegacyTable.CONDITION_LINK), counters);
        assertThat(clBadTask.violations()).extracting(LegacyViolation::column).contains("task_id");

        LegacyRowMapper.Mapped clBadIssue = mapper.map(LegacyTable.CONDITION_LINK,
                row("condition_id", "C-1", "task_id", null, "defect_id", "NOT_ISSUE", "created_by", "owner:admin"),
                permissiveRules(LegacyTable.CONDITION_LINK), counters);
        assertThat(clBadIssue.violations()).extracting(LegacyViolation::column).contains("issue_id");

        LegacyRowMapper.Mapped clGoodIssue = mapper.map(LegacyTable.CONDITION_LINK,
                row("condition_id", "C-1", "task_id", null, "defect_id", "PRJ-I-0001", "created_by", "owner:admin"),
                permissiveRules(LegacyTable.CONDITION_LINK), counters);
        assertThat(clGoodIssue.violations()).isEmpty();
        assertThat(clGoodIssue.row().text("link_key")).isNotBlank();

        // SESSION_GRANT: bad scope/author/granted_by/project vs valid revoked vs valid active
        LegacyRowMapper.Mapped grantBad = mapper.map(LegacyTable.SESSION_GRANT,
                row("id", 1L, "author_id", "badauthor", "project", "BAD PROJ", "scope", "unknown_scope",
                        "granted_by", "badgranter", "granted_at", Instant.now(), "expires_at", Instant.now(),
                        "revoked_at", null, "note", null),
                permissiveRules(LegacyTable.SESSION_GRANT), counters);
        assertThat(grantBad.row().text("active_key")).isNull();
        assertThat(grantBad.violations()).extracting(LegacyViolation::column)
                .contains("grant_scope", "author_id", "granted_by", "project_id");

        LegacyRowMapper.Mapped grantRevoked = mapper.map(LegacyTable.SESSION_GRANT,
                row("id", 7L, "author_id", "codex:sess1", "project", "proj_a", "scope", "defect",
                        "granted_by", "owner:admin", "granted_at", Instant.now(), "expires_at", Instant.now(),
                        "revoked_at", Instant.now(), "note", "done"),
                permissiveRules(LegacyTable.SESSION_GRANT), counters);
        assertThat(grantRevoked.violations()).isEmpty();
        assertThat(grantRevoked.row().text("active_key")).contains("7");

        // HISTORY: blank reason, unknown entity/op/assurance, mapped vs unmapped from_state/to_state
        LegacyRowMapper.Mapped histBad = mapper.map(LegacyTable.HISTORY,
                row("id", 1L, "at", Instant.now(), "author_id", "owner:admin", "login", "ledger_app",
                        "actor_assurance", "BOGUS_ASSURANCE", "entity", "unknown_ent", "entity_id", "E-1",
                        "op", "BOGUS_OP", "from_state", "SOURCE_FIXED", "to_state", "CUSTOM_STATE",
                        "changes", null, "reason", "   ", "evidence_uri", null, "evidence_sha256", null,
                        "corrects_id", null, "request_id", "req-1", "legacy_ref", "ref-1"),
                permissiveRules(LegacyTable.HISTORY), counters);
        assertThat(histBad.row().text("from_state")).isEqualTo("RESOLVED");
        assertThat(histBad.row().text("to_state")).isEqualTo("CUSTOM_STATE");
        assertThat(histBad.violations()).extracting(LegacyViolation::column)
                .contains("entity", "actor_assurance", "op", "reason");
    }
}
