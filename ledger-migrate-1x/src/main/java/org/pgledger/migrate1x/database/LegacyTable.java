// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.migrate1x.database;

import static org.pgledger.migrate1x.database.LegacyColumn.flag;
import static org.pgledger.migrate1x.database.LegacyColumn.instant;
import static org.pgledger.migrate1x.database.LegacyColumn.number;
import static org.pgledger.migrate1x.database.LegacyColumn.text;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The tables of the legacy import, in the order in which they are copied (a table comes after the tables its foreign keys
 * point to): for each the table of the legacy schema {@code ledger} that is read, the table of the new schema that is
 * written, the columns on both sides and the key. The legacy side is the schema of {@code sql/10_tables.sql} of 1.x (tag v1.1.0)
 * with the later migrations {@code 15_defect_case.sql}, {@code 16_common_code.sql} and {@code 17_created_by.sql} of 1.x applied. The
 * statements that read and write the tables are built here from the column lists and are plain ANSI SQL: a select list, a
 * table, an {@code ORDER BY}, an {@code INSERT} with one placeholder per column. The schema of the legacy side is an
 * identifier and so cannot be a bind value; the caller passes a validated name.
 *
 * <p>{@code task} and {@code milestone_criterion} have a column of the new schema that the legacy tables do not: {@code id_key},
 * the id in upper case under a UNIQUE constraint (the mapping makes it from the legacy id, like the link key).
 *
 * <p>The new schema uses universal 2.0 domain names and avoids words that SQL:2003 reserves: {@code defect} becomes {@code issue}
 * ({@code behavior} becomes {@code symptom}), {@code defect_fix} becomes {@code deliverable}, {@code defect_case} becomes
 * {@code issue_case} ({@code user_quote} becomes {@code source_quote}), {@code defect_task} becomes {@code issue_task},
 * {@code defect_relation} becomes {@code issue_relation}, {@code condition} becomes {@code milestone_criterion}
 * ({@code release} becomes {@code milestone_name}, {@code condition_text} becomes {@code criterion_text}),
 * {@code condition_link} becomes {@code criterion_link}, {@code history.at} becomes {@code occurred_at},
 * {@code history.legacy_ref} becomes {@code source_ref}, {@code history.request_id} becomes {@code operation_key}, and
 * {@code session_grant.scope} becomes {@code grant_scope}. The legacy side keeps the names the old installer
 * created; the mapper of the import maps one to the other.
 *
 * <p>The tables that are not copied: {@code _migration} (bookkeeping of the legacy installer, read only to check its
 * steps), {@code common_code_use} (the legacy common-code bookkeeping), the view {@code category} (the categories are
 * the rows of {@code common_code}) and {@code request_receipt} (the idempotency records of 1.x requests: every write of 2.0
 * carries an {@code operationKey} and a call with the old {@code request_id} is refused as an unknown argument, so a 1.x
 * answer that was lost cannot cause a second write in 2.0, and the rows are not carried over).
 * {@code spec}, {@code issue_spec}, {@code spec_task}, {@code session_key}, {@code session_token} and {@code owner_audit} are
 * new in this schema; the keys or tokens are issued by the owner after the import and the import writes its own audit row.
 */
public enum LegacyTable {

    PROJECT("project", "project", List.of("id"), List.of("id"),
            List.of(text("id"), text("code"), text("title"), text("created_by")),
            List.of(text("id"), text("code"), text("title"), number("next_issue_no"), number("next_spec_no"),
                    number("next_task_no"), number("version"), text("created_by")), false),

    COMMON_CODE("common_code", "common_code", List.of("code_group", "code"), List.of("code_group", "code"),
            List.of(text("code_group"), text("code"), text("name_ko"), text("name_en"), text("description_ko"),
                    text("description_en"), number("sort_order"), flag("active"), text("created_by")),
            List.of(text("code_group"), text("code"), text("name_ko"), text("name_en"), text("description_ko"),
                    text("description_en"), number("sort_order"), text("state"), text("created_by")), true),

    TASK("task", "task", List.of("id"), List.of("id"),
            List.of(text("id"), text("project_id"), text("title"), text("purpose"), text("success_condition"),
                    text("state"), text("owner_actor_id"), text("origin_ref"), text("origin_quote"), number("version"),
                    instant("created_at"), instant("updated_at"), text("created_by")),
            List.of(text("id"), text("id_key"), text("project_id"), text("title"), text("purpose"),
                    text("success_condition"), text("state"), text("owner_actor_id"), text("origin_ref"),
                    text("origin_quote"), number("version"), instant("created_at"), instant("updated_at"),
                    text("created_by")), false),

    DEFECT("defect", "issue", List.of("id"), List.of("id"),
            List.of(text("id"), text("project_id"), text("title"), text("category"), text("severity"), text("status"),
                    text("merged_into"), text("behavior"), text("root_cause"), text("owner_actor_id"), number("version"),
                    instant("created_at"), instant("updated_at"), text("created_by")),
            List.of(text("id"), text("project_id"), text("title"), text("category"), text("severity"), text("status"),
                    text("merged_into"), text("symptom"), text("root_cause"), text("owner_actor_id"), number("case_seq"),
                    number("version"), instant("created_at"), instant("updated_at"), text("created_by")), false),

    DEFECT_FIX("defect_fix", "deliverable", List.of("id"), List.of("id"),
            List.of(number("id"), text("defect_id"), text("repo"), text("commit_sha"), text("branch"), text("ci_ref"),
                    text("ci_conclusion"), instant("ci_completed_at"), text("recorded_by"), instant("recorded_at"),
                    flag("imported")),
            List.of(number("id"), text("target_entity"), text("target_id"), text("kind"), text("deliverable_ref"),
                    text("location"), text("summary"), text("check_ref"), text("check_result"), instant("checked_at"),
                    text("recorded_by"), instant("recorded_at"), text("origin")), false),

    DEFECT_CASE("defect_case", "issue_case", List.of("id"), List.of("id"),
            List.of(number("id"), text("defect_id"), text("observation"), text("evidence"), text("user_quote"),
                    instant("observed_at"), text("recorded_by"), instant("recorded_at")),
            List.of(number("id"), text("issue_id"), text("observation"), text("evidence"), text("source_quote"),
                    instant("observed_at"), text("recorded_by"), instant("recorded_at")), false),

    DEFECT_TASK("defect_task", "issue_task", List.of("defect_id", "task_id"), List.of("issue_id", "task_id"),
            List.of(text("defect_id"), text("task_id"), text("created_by")),
            List.of(text("issue_id"), text("task_id"), text("created_by")), false),

    DEFECT_RELATION("defect_relation", "issue_relation", List.of("a", "b", "kind"), List.of("a", "b", "kind"),
            List.of(text("a"), text("b"), text("kind"), text("created_by")),
            List.of(text("a"), text("b"), text("kind"), text("created_by")), false),

    /** The legacy table is named {@code condition} and its milestone column {@code release}; both are reserved words. */
    RELEASE_CONDITION("condition", "milestone_criterion", List.of("id"), List.of("id"),
            List.of(text("id"), text("project_id"), text("release"), text("goal_label"), text("code"),
                    text("condition_text"), text("state"), number("version"), text("created_by")),
            List.of(text("id"), text("id_key"), text("project_id"), text("milestone_name"), text("goal_label"),
                    text("code"), text("criterion_text"), text("state"), number("version"), text("created_by")), false),

    /** The legacy table has no key column: the new schema's {@code link_key} is made from the target id columns. */
    CONDITION_LINK("condition_link", "criterion_link", List.of("condition_id"), List.of("link_key"),
            List.of(text("condition_id"), text("task_id"), text("defect_id"), text("created_by")),
            List.of(text("link_key"), text("criterion_id"), text("task_id"), text("issue_id"), text("spec_id"),
                    text("created_by")), false),

    SESSION_GRANT("session_grant", "session_grant", List.of("id"), List.of("id"),
            List.of(number("id"), text("author_id"), text("project"), text("scope"), text("granted_by"),
                    instant("granted_at"), instant("expires_at"), instant("revoked_at"), text("note")),
            List.of(number("id"), text("author_id"), text("project_id"), text("grant_scope"), text("granted_by"),
                    instant("granted_at"), instant("expires_at"), instant("revoked_at"), instant("last_used_at"),
                    text("note"), text("active_key")), false),

    HISTORY("history", "history", List.of("id"), List.of("id"),
            List.of(number("id"), instant("at"), text("author_id"), text("login"), text("actor_assurance"),
                    text("entity"), text("entity_id"), text("op"), text("from_state"), text("to_state"), text("changes"),
                    text("reason"), text("evidence_uri"), text("evidence_sha256"), number("corrects_id"),
                    text("request_id"), text("legacy_ref")),
            List.of(number("id"), instant("occurred_at"), text("author_id"), text("login"), text("actor_assurance"),
                    text("entity"), text("entity_id"), text("op"), text("from_state"), text("to_state"), text("changes"),
                    text("before_data"), text("reason"), text("evidence_uri"), text("evidence_sha256"),
                    number("corrects_id"), text("operation_key"), text("source_ref")), false);

    private final String sourceName;
    private final String targetName;
    private final List<String> sourceKey;
    private final List<String> targetKey;
    private final List<LegacyColumn> sourceColumns;
    private final List<LegacyColumn> targetColumns;
    private final boolean targetMayHoldOtherRows;

    LegacyTable(String sourceName, String targetName, List<String> sourceKey, List<String> targetKey,
            List<LegacyColumn> sourceColumns, List<LegacyColumn> targetColumns, boolean targetMayHoldOtherRows) {
        this.sourceName = sourceName;
        this.targetName = targetName;
        this.sourceKey = sourceKey;
        this.targetKey = targetKey;
        this.sourceColumns = sourceColumns;
        this.targetColumns = targetColumns;
        this.targetMayHoldOtherRows = targetMayHoldOtherRows;
    }

    /** The table of the legacy schema. */
    public String sourceName() {
        return sourceName;
    }

    /** The table of the new schema. */
    public String targetName() {
        return targetName;
    }

    /** The key columns of the legacy table (the order of the rows read, and what names a row in a refusal). */
    public List<String> sourceKey() {
        return sourceKey;
    }

    /** The key columns of the new table (what orders the rows of a digest). */
    public List<String> targetKey() {
        return targetKey;
    }

    public List<LegacyColumn> sourceColumns() {
        return sourceColumns;
    }

    public List<LegacyColumn> targetColumns() {
        return targetColumns;
    }

    /**
     * True when the new table already holds rows of a fresh installation that are not copied from the legacy table (the
     * common codes the schema seeds): the copy then updates them, and the comparison looks at the copied keys only.
     */
    public boolean targetMayHoldOtherRows() {
        return targetMayHoldOtherRows;
    }

    /** The statement that reads the legacy table in key order; {@code schema} is a validated identifier. */
    public String sourceSelect(String schema) {
        return "SELECT " + names(sourceColumns) + " FROM " + schema + "." + sourceName + " ORDER BY "
                + String.join(", ", sourceKey);
    }

    /** The statement that reads the new table (no order: the digest orders the rows itself). */
    public String targetSelect() {
        return "SELECT " + names(targetColumns) + " FROM " + targetName;
    }

    /** The statement that inserts one row of the new table, one positional placeholder per column. */
    public String targetInsert() {
        return "INSERT INTO " + targetName + " (" + names(targetColumns) + ") VALUES ("
                + targetColumns.stream().map(column -> "?").collect(Collectors.joining(", ")) + ")";
    }

    private static String names(List<LegacyColumn> columns) {
        return columns.stream().map(LegacyColumn::name).collect(Collectors.joining(", "));
    }
}
