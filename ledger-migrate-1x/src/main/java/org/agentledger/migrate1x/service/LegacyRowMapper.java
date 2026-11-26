// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.migrate1x.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.agentledger.domain.error.ValidationReason;
import org.agentledger.domain.model.CriterionLink;
import org.agentledger.domain.model.Deliverable;
import org.agentledger.domain.model.SessionGrant;
import org.agentledger.domain.rule.ActorAssurance;
import org.agentledger.domain.rule.CheckResult;
import org.agentledger.domain.rule.CodeGroup;
import org.agentledger.domain.rule.CodeState;
import org.agentledger.domain.rule.CriterionState;
import org.agentledger.domain.rule.DeliverableOrigin;
import org.agentledger.domain.rule.DeliverableTarget;
import org.agentledger.domain.rule.HistoryEntity;
import org.agentledger.domain.rule.HistoryOp;
import org.agentledger.domain.rule.IssueStatus;
import org.agentledger.domain.rule.Scope;
import org.agentledger.domain.rule.TaskState;
import org.agentledger.domain.value.AuthorId;
import org.agentledger.domain.value.CodeValue;
import org.agentledger.domain.value.CriterionId;
import org.agentledger.domain.value.EntityId;
import org.agentledger.domain.value.IssueId;
import org.agentledger.domain.value.ProjectCode;
import org.agentledger.domain.value.ProjectId;
import org.agentledger.domain.value.TaskId;
import org.agentledger.migrate1x.database.ColumnRule;
import org.agentledger.migrate1x.database.LegacyColumn;
import org.agentledger.migrate1x.database.LegacyRow;
import org.agentledger.migrate1x.database.LegacyTable;
import org.springframework.stereotype.Component;

/**
 * Turns a row of the legacy schema into the row of the new schema, or says why it cannot be. It is a pure function of the
 * row, the column rules of the new schema and the numbers the legacy ledger used; it touches no database.
 */
@Component
final class LegacyRowMapper {

    /** The row of the new schema, and everything that is wrong with it (empty when it can be written). */
    record Mapped(LegacyRow row, List<LegacyViolation> violations) {
    }

    private static final Map<String, String> CODE_GROUPS = Map.of(
            "DEFECT_CATEGORY", CodeGroup.ISSUE_CATEGORY.name(),
            "DEFECT_SEVERITY", CodeGroup.ISSUE_SEVERITY.name(),
            "RELATION_KIND", CodeGroup.RELATION_KIND.name());

    private static final Map<String, String> TASK_STATES = taskStates();
    private static final Map<String, String> ISSUE_STATUSES = issueStatuses();
    private static final Set<String> CRITERION_STATES = names(CriterionState.values());

    private static final Map<String, String> CHECK_RESULTS = Map.of(
            "success", CheckResult.PASSED.name(),
            "failure", CheckResult.FAILED.name(),
            "cancelled", CheckResult.CANCELLED.name(),
            "passed", CheckResult.PASSED.name(),
            "failed", CheckResult.FAILED.name());

    private static final Map<String, Scope> GRANT_SCOPES = Map.of(
            "defect", Scope.ISSUE,
            "fix", Scope.DELIVERABLE,
            "task", Scope.TASK,
            "verify", Scope.VERIFY,
            "issue", Scope.ISSUE,
            "spec", Scope.SPEC,
            "deliverable", Scope.DELIVERABLE);

    private static final Map<String, String> HISTORY_ENTITIES = Map.of(
            "defect", HistoryEntity.ISSUE.name(),
            "defect_fix", HistoryEntity.DELIVERABLE.name(),
            "defect_case", HistoryEntity.ISSUE_CASE.name(),
            "defect_task", HistoryEntity.ISSUE_TASK.name(),
            "defect_relation", HistoryEntity.ISSUE_RELATION.name(),
            "condition", HistoryEntity.CRITERION.name(),
            "condition_link", HistoryEntity.CRITERION_LINK.name(),
            "task", HistoryEntity.TASK.name());

    private static final Map<String, String> HISTORY_STATES = Map.of(
            "SOURCE_FIXED", IssueStatus.RESOLVED.name(),
            "NOT_DEFECT", IssueStatus.NOT_AN_ISSUE.name(),
            "REPORTED_DONE", TaskState.COMPLETED.name());

    /** The assurances and operations as 1.x wrote them (its {@code LEGACY_} names) and the constants of 2.0 they become. */
    private static final Map<String, String> ASSURANCES = oneXNames(ActorAssurance.values());
    private static final Map<String, String> HISTORY_OPS = oneXNames(HistoryOp.values());
    /** How the refusal of an author names the format (the domain has the rule, not a sentence for it). */
    private static final String AUTHOR_FORMAT = "<vendor>:<session-id>";
    private static final String IMPORTED_FIX_SUMMARY = "imported 1.x commit";

    private static <E extends Enum<E>> Set<String> names(E[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toUnmodifiableSet());
    }

    private static Map<String, String> taskStates() {
        Map<String, String> map = new LinkedHashMap<>();
        for (TaskState state : TaskState.values()) {
            map.put(state.name(), state.name());
        }
        map.put("REPORTED_DONE", TaskState.COMPLETED.name());
        return Map.copyOf(map);
    }

    private static Map<String, String> issueStatuses() {
        Map<String, String> map = new LinkedHashMap<>();
        for (IssueStatus status : IssueStatus.values()) {
            map.put(status.name(), status.name());
        }
        map.put("SOURCE_FIXED", IssueStatus.RESOLVED.name());
        map.put("NOT_DEFECT", IssueStatus.NOT_AN_ISSUE.name());
        return Map.copyOf(map);
    }

    private static final String IMPORTED_PREFIX = "IMPORTED_";
    private static final String ONE_X_PREFIX = "LEGACY_";

    /** The name 1.x wrote for each constant, to the name of the constant: {@code IMPORTED_X} was {@code LEGACY_X}. */
    private static <E extends Enum<E>> Map<String, String> oneXNames(E[] values) {
        return Arrays.stream(values).collect(Collectors.toUnmodifiableMap(
                value -> value.name().startsWith(IMPORTED_PREFIX)
                        ? ONE_X_PREFIX + value.name().substring(IMPORTED_PREFIX.length()) : value.name(),
                Enum::name));
    }

    /** Collects what is wrong with one row. */
    private static final class Violations {

        private final LegacyTable table;
        private final String key;
        private final List<LegacyViolation> found = new ArrayList<>();

        Violations(LegacyTable table, String key) {
            this.table = table;
            this.key = key;
        }

        void add(String column, ValidationReason reason, String... reasonArgs) {
            found.add(new LegacyViolation(table.targetName(), key, column, reason, List.of(reasonArgs)));
        }

        /** True when the column of this row has been reported already (a value is reported once, for its first reason). */
        boolean reported(String column) {
            return found.stream().anyMatch(violation -> Objects.equals(violation.column(), column));
        }
    }

    /**
     * @param table    the legacy table the row was read from
     * @param source   the row as read
     * @param rules    what the new table accepts, by column (from the database metadata)
     * @param counters the numbers the legacy ledger used (the counters of a project row come from here)
     */
    Mapped map(LegacyTable table, LegacyRow source, Map<String, ColumnRule> rules, ImportCounters counters) {
        Violations violations = new Violations(table, keyOf(table, source));
        LegacyRow s = normalised(source);
        Map<String, Object> t = new LinkedHashMap<>();
        switch (table) {
            case PROJECT -> project(s, t, counters, violations);
            case COMMON_CODE -> commonCode(s, t, violations);
            case TASK -> task(s, t, violations);
            case DEFECT -> issue(s, t, violations);
            case DEFECT_FIX -> deliverable(s, t, violations);
            case DEFECT_CASE -> issueCase(s, t);
            case DEFECT_TASK -> issueTask(s, t);
            case DEFECT_RELATION -> issueRelation(s, t, violations);
            case RELEASE_CONDITION -> milestoneCriterion(s, t, violations);
            case CONDITION_LINK -> criterionLink(s, t, violations);
            case SESSION_GRANT -> sessionGrant(s, t, violations);
            case HISTORY -> history(s, t, violations);
        }
        Set<String> expected = table.targetColumns().stream().map(LegacyColumn::name).collect(Collectors.toSet());
        if (!Objects.equals(expected, t.keySet())) {
            throw new IllegalStateException("the mapping of " + table + " fills " + t.keySet() + " but the table has "
                    + expected);
        }
        LegacyRow target = new LegacyRow(t);
        checkColumns(table, target, rules, violations);
        return new Mapped(target, List.copyOf(violations.found));
    }

    /** The key of the legacy row, as a refusal names it. */
    private static String keyOf(LegacyTable table, LegacyRow source) {
        return table.sourceKey().stream().map(column -> String.valueOf(source.get(column)))
                .collect(Collectors.joining("|"));
    }

    /**
     * The columns of the 1.x schema that hold who acted. 1.x wrote a session as {@code agent:<vendor>:<session>}; 2.0 knows it as
     * {@code <vendor>:<session>} (the same session as the one that signs a write now), so the prefix is taken off here and nowhere
     * else (M1X-050): the checks that two actions are of one session (VERIFIED needs a fix of another session, the owner and the
     * reporter of a task) would otherwise see two sessions.
     */
    private static final Set<String> ACTOR_COLUMNS = Set.of("author_id", "recorded_by", "owner_actor_id", "created_by",
            "granted_by");
    private static final String AGENT_PREFIX = "agent:";

    /** The row with every empty text as NULL, every time cut to microseconds and every actor id in its 2.0 form. */
    private static LegacyRow normalised(LegacyRow source) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.values().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String text && text.isEmpty()) {
                value = null;
            } else if (value instanceof Instant instant) {
                value = instant.truncatedTo(ChronoUnit.MICROS);
            } else if (value instanceof String text && ACTOR_COLUMNS.contains(entry.getKey())
                    && text.startsWith(AGENT_PREFIX) && text.length() > AGENT_PREFIX.length()) {
                value = text.substring(AGENT_PREFIX.length());
            }
            values.put(entry.getKey(), value);
        }
        return new LegacyRow(values);
    }

    /** NOT NULL and the width of a text column, from what the new schema says (MIG-023, MIG-015). */
    private static void checkColumns(LegacyTable table, LegacyRow target, Map<String, ColumnRule> rules,
            Violations violations) {
        for (LegacyColumn column : table.targetColumns()) {
            ColumnRule rule = rules.get(column.name());
            if (rule == null) {
                throw new IllegalStateException("the new schema has no column " + table.targetName() + "."
                        + column.name());
            }
            Object value = target.get(column.name());
            if (value == null) {
                if (!rule.nullable()) {
                    violations.add(column.name(), ValidationReason.REQUIRED);
                }
            } else if (value instanceof String text && rule.maxChars() > 0 && !violations.reported(column.name())
                    && text.codePointCount(0, text.length()) > rule.maxChars()) {
                violations.add(column.name(), ValidationReason.MUST_BE_AT_MOST_CHARS, String.valueOf(rule.maxChars()));
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ the tables

    private static void project(LegacyRow s, Map<String, Object> t, ImportCounters counters, Violations v) {
        copy(s, t, "id", "code", "title", "created_by");
        matches(v, "id", s.text("id"), ProjectId.FORMAT, ValidationReason.MUST_MATCH);
        matches(v, "code", s.text("code"), ProjectCode.FORMAT, ValidationReason.MUST_MATCH);
        t.put("next_issue_no", counters.issueNumber(s.text("id")));
        t.put("next_spec_no", 0L);
        t.put("next_task_no", counters.taskNumber(s.text("id")));
        t.put("version", 1L);
    }

    private static void commonCode(LegacyRow s, Map<String, Object> t, Violations v) {
        copy(s, t, "code", "name_ko", "name_en", "description_ko", "description_en", "sort_order", "created_by");
        t.put("code_group", renamed(v, "code_group", s.text("code_group"), CODE_GROUPS));
        matches(v, "code", s.text("code"), CodeValue.FORMAT, ValidationReason.MUST_MATCH);
        t.put("state", (s.flag("active") ? CodeState.ACTIVE : CodeState.INACTIVE).name());
    }

    private static void task(LegacyRow s, Map<String, Object> t, Violations v) {
        copy(s, t, "id", "project_id", "title", "purpose", "success_condition", "owner_actor_id",
                "origin_ref", "origin_quote", "version", "created_at", "updated_at", "created_by");
        entityId(v, "id", s.text("id"));
        t.put("id_key", idKey(s.text("id")));
        t.put("state", renamed(v, "state", s.text("state"), TASK_STATES));
    }

    private static void issue(LegacyRow s, Map<String, Object> t, Violations v) {
        copy(s, t, "id", "project_id", "title", "category", "severity", "merged_into",
                "root_cause", "owner_actor_id", "version", "created_at", "updated_at", "created_by");
        t.put("symptom", s.get("behavior"));
        String id = s.text("id");
        if (id != null && !IssueId.FORMAT.matcher(id).matches()) {
            v.add("id", ValidationReason.ISSUE_ID_FORMAT);
        }
        matches(v, "category", s.text("category"), CodeValue.FORMAT, ValidationReason.MUST_MATCH);
        matches(v, "severity", s.text("severity"), CodeValue.FORMAT, ValidationReason.MUST_MATCH);
        t.put("status", renamed(v, "status", s.text("status"), ISSUE_STATUSES));
        t.put("case_seq", 0L);
    }

    private static void deliverable(LegacyRow s, Map<String, Object> t, Violations v) {
        t.put("id", s.get("id"));
        t.put("target_entity", DeliverableTarget.ISSUE.name());
        t.put("target_id", s.text("defect_id"));
        t.put("kind", "code_change");
        t.put("deliverable_ref", s.text("commit_sha"));
        String repo = s.text("repo");
        t.put("location", repo);
        t.put("location_key", Deliverable.locationKeyOf(repo));
        if (repo == null) {
            v.add("location", ValidationReason.REQUIRED);
        }
        String branch = s.text("branch");
        t.put("summary", branch != null ? "branch " + branch : IMPORTED_FIX_SUMMARY);
        String checkRef = s.text("ci_ref");
        t.put("check_ref", checkRef);
        String checkResult = renamedNull(v, "check_result", s.text("ci_conclusion"), CHECK_RESULTS);
        t.put("check_result", checkResult);
        if (checkResult != null && checkRef == null) {
            v.add("check_ref", ValidationReason.REQUIRED);
        }
        t.put("checked_at", s.get("ci_completed_at"));
        t.put("recorded_by", s.text("recorded_by"));
        t.put("recorded_at", s.get("recorded_at"));
        t.put("origin", (s.flag("imported") ? DeliverableOrigin.IMPORTED : DeliverableOrigin.NATIVE).name());
    }

    private static void issueCase(LegacyRow s, Map<String, Object> t) {
        copy(s, t, "id", "observation", "evidence", "observed_at", "recorded_by", "recorded_at");
        t.put("issue_id", s.text("defect_id"));
        t.put("source_quote", s.text("user_quote"));
    }

    private static void issueTask(LegacyRow s, Map<String, Object> t) {
        t.put("issue_id", s.text("defect_id"));
        copy(s, t, "task_id", "created_by");
    }

    private static void issueRelation(LegacyRow s, Map<String, Object> t, Violations v) {
        copy(s, t, "a", "b", "kind", "created_by");
        matches(v, "kind", s.text("kind"), CodeValue.FORMAT, ValidationReason.MUST_MATCH);
    }

    private static void milestoneCriterion(LegacyRow s, Map<String, Object> t, Violations v) {
        copy(s, t, "id", "project_id", "goal_label", "code", "state", "version", "created_by");
        t.put("milestone_name", s.text("release"));
        t.put("criterion_text", s.text("condition_text"));
        entityId(v, "id", s.text("id"));
        t.put("id_key", idKey(s.text("id")));
        oneOf(v, "state", s.text("state"), CRITERION_STATES);
    }

    /** An id of a task or criterion that has the form of 1.x (the id of an issue is {@link IssueId}); it is never case-folded. */
    private static void entityId(Violations v, String column, String id) {
        if (id != null && !EntityId.PATTERN.matcher(id).matches()) {
            v.add(column, ValidationReason.MUST_BE_ID, Integer.toString(EntityId.MAX_LENGTH));
        }
    }

    /** The upper-case key of the id (the UNIQUE column that refuses ids which differ only in case), NULL for no id. */
    private static String idKey(String id) {
        return id == null ? null : EntityId.key(id);
    }

    private static void criterionLink(LegacyRow s, Map<String, Object> t, Violations v) {
        String criterion = s.text("condition_id");
        String task = s.text("task_id");
        String issue = s.text("defect_id");
        t.put("criterion_id", criterion);
        t.put("task_id", task);
        t.put("issue_id", issue);
        t.put("spec_id", null);
        t.put("created_by", s.text("created_by"));
        if ((task == null) == (issue == null)) {
            v.add("task_id", ValidationReason.MUST_BE_EXACTLY_ONE_OF, "task_id", "issue_id");
        }
        boolean criterionValid = criterion != null && EntityId.PATTERN.matcher(criterion).matches();
        if (criterion != null && !criterionValid) {
            v.add("criterion_id", ValidationReason.MUST_BE_ID, Integer.toString(EntityId.MAX_LENGTH));
        }
        t.put("link_key", criterionValid ? linkKey(CriterionId.of(criterion), task, issue, v) : null);
    }

    /** The link key of a criterion and exactly one of a task and an issue, reporting an id that is none. */
    private static String linkKey(CriterionId criterion, String task, String issue, Violations v) {
        if (task != null && issue == null) {
            if (!EntityId.PATTERN.matcher(task).matches()) {
                v.add("task_id", ValidationReason.MUST_BE_ID, Integer.toString(EntityId.MAX_LENGTH));
                return null;
            }
            return CriterionLink.toTask(criterion, TaskId.of(task), AuthorId.OWNER).linkKey();
        }
        if (issue != null && task == null) {
            if (!IssueId.FORMAT.matcher(issue).matches()) {
                v.add("issue_id", ValidationReason.ISSUE_ID_FORMAT);
                return null;
            }
            return CriterionLink.toIssue(criterion, IssueId.of(issue), AuthorId.OWNER).linkKey();
        }
        return null;
    }

    private static void sessionGrant(LegacyRow s, Map<String, Object> t, Violations v) {
        copy(s, t, "id", "author_id", "granted_by", "granted_at", "expires_at", "revoked_at", "note");
        t.put("project_id", s.text("project"));
        t.put("last_used_at", null);
        String rawScope = s.text("scope");
        Scope mappedScope = rawScope == null ? null : GRANT_SCOPES.get(rawScope);
        if (rawScope != null && mappedScope == null) {
            v.add("grant_scope", ValidationReason.UNKNOWN_VALUE, rawScope);
        }
        // the new schema names the column grant_scope (SCOPE is a reserved word of SQL:2003); the legacy one is scope
        t.put("grant_scope", mappedScope == null ? null : mappedScope.name());
        String author = s.text("author_id");
        String project = s.text("project");
        boolean authorValid = AuthorId.matches(author);
        if (author != null && !authorValid) {
            v.add("author_id", ValidationReason.MUST_MATCH, AUTHOR_FORMAT);
        }
        String grantedBy = s.text("granted_by");
        if (grantedBy != null && !AuthorId.matches(grantedBy)) {
            v.add("granted_by", ValidationReason.MUST_MATCH, AUTHOR_FORMAT);
        }
        boolean projectValid = project != null && ProjectId.FORMAT.matcher(project).matches();
        if (project != null && !projectValid) {
            v.add("project_id", ValidationReason.MUST_MATCH, ProjectId.FORMAT.pattern());
        }
        String activeKey = null;
        if (s.instant("revoked_at") != null) {
            activeKey = SessionGrant.revokedKey(s.number("id"));
        } else if (authorValid && projectValid && mappedScope != null) {
            activeKey = SessionGrant.liveKey(new AuthorId(author), new ProjectId(project), mappedScope);
        }
        t.put("active_key", activeKey);
    }

    private static void history(LegacyRow s, Map<String, Object> t, Violations v) {
        copy(s, t, "id", "author_id", "login", "entity_id", "changes", "reason", "evidence_uri",
                "evidence_sha256", "corrects_id");
        // the new schema names the column occurred_at (AT is a reserved word of SQL:2003); the legacy one is at
        t.put("occurred_at", s.get("at"));
        // and operation_key where 1.x had request_id: the request id of the 1.x write that made the row (a UUID text)
        t.put("operation_key", s.get("request_id"));
        // and source_ref where 1.x had legacy_ref: the reference of the row in the ledger it was imported from
        t.put("source_ref", s.get("legacy_ref"));
        t.put("from_state", mapState(s.text("from_state")));
        t.put("to_state", mapState(s.text("to_state")));
        t.put("entity", renamedNull(v, "entity", s.text("entity"), HISTORY_ENTITIES));
        t.put("before_data", null);
        t.put("actor_assurance", renamed(v, "actor_assurance", s.text("actor_assurance"), ASSURANCES));
        t.put("op", renamed(v, "op", s.text("op"), HISTORY_OPS));
        String reason = s.text("reason");
        if (reason != null && reason.isBlank()) {
            // the history entry of the domain refuses a blank reason when it is read back
            v.add("reason", ValidationReason.REQUIRED);
        }
    }

    // ---------------------------------------------------------------------------------------------------- helpers

    private static String mapState(String raw) {
        if (raw == null) {
            return null;
        }
        String mapped = HISTORY_STATES.get(raw);
        return mapped != null ? mapped : raw;
    }

    /**
     * The name of the constant of 2.0 for the value as 1.x wrote it. A value that is none of them is reported and kept as it
     * is (the row is refused, nothing is written).
     */
    private static String renamed(Violations v, String column, String raw, Map<String, String> oneXNames) {
        if (raw == null) {
            return null;
        }
        String name = oneXNames.get(raw);
        if (name == null) {
            v.add(column, ValidationReason.UNKNOWN_VALUE, raw);
            return raw;
        }
        return name;
    }

    /** Maps a nullable or required token via a 1.x-to-2.0 lookup map, returning null and reporting UNKNOWN_VALUE on an unknown text. */
    private static String renamedNull(Violations v, String column, String raw, Map<String, String> oneXNames) {
        if (raw == null) {
            return null;
        }
        String name = oneXNames.get(raw);
        if (name == null) {
            v.add(column, ValidationReason.UNKNOWN_VALUE, raw);
            return null;
        }
        return name;
    }

    private static void copy(LegacyRow from, Map<String, Object> to, String... columns) {
        for (String column : columns) {
            to.put(column, from.get(column));
        }
    }

    private static void matches(Violations v, String column, String value, Pattern pattern, ValidationReason reason) {
        if (value != null && !pattern.matcher(value).matches()) {
            v.add(column, reason, pattern.pattern());
        }
    }

    private static void oneOf(Violations v, String column, String value, Set<String> allowed) {
        if (value != null && !allowed.contains(value)) {
            v.add(column, ValidationReason.UNKNOWN_VALUE, value);
        }
    }
}
