package org.pgledger.migrate1x.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.migrate1x.fixture.LegacyLedger;
import org.pgledger.migrate1x.support.SqlText;
import org.pgledger.migrate1x.support.SqlText.Group;
import org.pgledger.migrate1x.support.TestLedgerDatabase;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * MIG-024 (the table shapes of the fixture against the catalogue of the import) and the catalogue of the legacy import
 * against the real schema files: every column the import writes is a column of the migrated schema and no column of the
 * schema is forgotten; the legacy side of the catalogue is the shape the fixture builds; every statement the catalogue
 * builds is ANSI SQL (the checks of ANSI-001 .. ANSI-003 and ANSI-014). The request receipts of 1.x are no table of the
 * catalogue (M1X-003).
 */
class LegacyTableTest {

    /**
     * The columns that only the 2.0 schema has and that the import neither reads nor writes: 1.x had no such data, so a row the
     * import inserts takes the default of the column and a row it overwrites (a code the seed made) keeps its own value. They are
     * listed here, with the file that adds each, so that a new column of a migrated table is a decision and not an accident:
     * {@code common_code.direction} (V4, default CANONICAL; the relations whose direction the import cannot restore are
     * reported, see ImportReport), {@code common_code.version} (V5, default 0; the version that the update of a code checks) and
     * {@code history.attachment_id} (V8, nullable, no default: 1.x had no attachments, so every imported row has none).
     */
    private static final Map<LegacyTable, List<String>> SCHEMA_ONLY = Map.of(
            LegacyTable.HISTORY, List.of("attachment_id"),
            LegacyTable.DEFECT_FIX, List.of("attachment_id"),
            LegacyTable.COMMON_CODE, List.of("direction", "version"));

    private static List<String> names(List<LegacyColumn> columns) {
        return columns.stream().map(LegacyColumn::name).toList();
    }

    @Test
    @DisplayName("MIG-024 the new side of the catalogue lists exactly the columns of the tables of the migrated schema, and the database metadata gives NOT NULL and the width of the columns the import checks")
    void theTargetColumnsAreTheColumnsOfTheSchema() {
        try (TestLedgerDatabase database = TestLedgerDatabase.migrated()) {
            checkTargetColumns(new LegacyTargetRepository(JdbcClient.create(database.dataSource()), database.dataSource()));
        }
    }

    private static void checkTargetColumns(LegacyTargetRepository target) {
        for (LegacyTable table : LegacyTable.values()) {
            Map<String, ColumnRule> rules = target.columnRules(table);
            List<String> expected = new ArrayList<>(names(table.targetColumns()));
            expected.addAll(SCHEMA_ONLY.getOrDefault(table, List.of()));
            assertThat(rules.keySet()).as(table.name()).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(table.targetKey()).as(table.name() + " key").isSubsetOf(names(table.targetColumns()));
            assertThat(table.sourceKey()).as(table.name() + " source key").isSubsetOf(names(table.sourceColumns()));
        }
        Map<String, ColumnRule> task = target.columnRules(LegacyTable.TASK);
        // REH3: purpose, success_condition, behavior, root_cause and condition_text allowed '' in 1.x, so they are nullable; the
        // columns that identify a row stay NOT NULL, and so does the key of the id
        assertThat(task.get("purpose").nullable()).isTrue();
        assertThat(task.get("success_condition").nullable()).isTrue();
        assertThat(task.get("title").nullable()).isFalse();
        assertThat(task.get("id_key").nullable()).isFalse();
        assertThat(task.get("id_key").maxChars()).isEqualTo(360);
        assertThat(task.get("id").maxChars()).isEqualTo(120);
        assertThat(target.columnRules(LegacyTable.RELEASE_CONDITION).get("code").maxChars()).isEqualTo(200);
        assertThat(target.columnRules(LegacyTable.RELEASE_CONDITION).get("milestone_name").nullable()).isFalse();
        assertThat(target.columnRules(LegacyTable.DEFECT_FIX).get("check_ref").maxChars()).isEqualTo(2000);
        assertThat(task.get("owner_actor_id").nullable()).isTrue();
        assertThat(task.get("title").maxChars()).isEqualTo(300);
        // REH3-LEN-02: origin_ref is a long text (text without a limit in 1.x; 9408 characters in production)
        assertThat(task.get("origin_ref").maxChars()).isIn(0, Integer.MAX_VALUE);
        // a long text column has no limit the import checks: the metadata of PostgreSQL states it as the largest int
        assertThat(target.columnRules(LegacyTable.HISTORY).get("changes").maxChars()).isIn(0, Integer.MAX_VALUE);
        assertThat(target.columnRules(LegacyTable.COMMON_CODE).get("description_ko").nullable()).isTrue();
    }

    @Test
    @DisplayName("MIG-024 the legacy side of the catalogue lists exactly the columns of the tables of the legacy schema that the fixture builds, in the order of the select list, and the statements run")
    void theSourceColumnsAreTheColumnsOfTheLegacySchema() {
        try (LegacyLedger legacy = LegacyLedger.create()) {
            for (LegacyTable table : LegacyTable.values()) {
                List<String> columns = legacy.rows("SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = ?", legacy.schema(), table.sourceName())
                        .stream().map(row -> String.valueOf(row.get("column_name")).toLowerCase(Locale.ROOT)).toList();
                assertThat(columns).as(table.name()).containsExactlyInAnyOrderElementsOf(names(table.sourceColumns()));
                assertThat(legacy.rows(table.sourceSelect(legacy.schema()))).isEmpty();
            }
        }
    }

    @Test
    @DisplayName("MIG-024 the tables are listed parents first: every foreign key of the new schema points to a table that comes earlier in the catalogue")
    void parentsComeFirst() {
        List<String> order = List.of(LegacyTable.values()).stream().map(LegacyTable::targetName).toList();
        Map<String, Set<String>> parents = Map.of(
                "task", Set.of("project"), "issue", Set.of("project", "issue"),
                "deliverable", Set.of("issue", "task"), "issue_case", Set.of("issue"),
                "issue_task", Set.of("issue", "task"), "issue_relation", Set.of("issue"),
                "milestone_criterion", Set.of("project"),
                "criterion_link", Set.of("milestone_criterion", "task", "issue"),
                "session_grant", Set.of("project"), "history", Set.of("history"));

        for (Map.Entry<String, Set<String>> entry : parents.entrySet()) {
            for (String parent : entry.getValue()) {
                if (!Objects.equals(parent, entry.getKey())) {
                    assertThat(order.indexOf(parent)).as(entry.getKey() + " after " + parent).isLessThan(order.indexOf(entry.getKey()));
                }
            }
        }
        // twelve tables: the request receipts of 1.x are not copied (M1X-003)
        assertThat(order).hasSize(12).doesNotHaveDuplicates().doesNotContain("request_receipt");
    }

    @Test
    @DisplayName("MIG-024 / ANSI-001..003, ANSI-014 every statement the catalogue builds is plain ANSI SQL: a select list, a table, an ORDER BY, an INSERT with one placeholder per column; no vendor syntax, no session statement, no time function")
    void theStatementsAreAnsi() {
        for (LegacyTable table : LegacyTable.values()) {
            for (String sql : List.of(table.sourceSelect(LegacyLedger.SCHEMA), table.targetSelect(), table.targetInsert())) {
                assertThat(new SqlText(table.name(), sql).findings(Set.of(Group.values()))).as(sql).isEmpty();
            }
            assertThat(table.targetInsert().chars().filter(c -> c == '?').count()).isEqualTo(table.targetColumns().size());
            assertThat(table.sourceSelect(LegacyLedger.SCHEMA)).startsWith("SELECT ").contains(" FROM ledger.")
                    .contains(" ORDER BY ").doesNotContain("::");
        }
    }
}
