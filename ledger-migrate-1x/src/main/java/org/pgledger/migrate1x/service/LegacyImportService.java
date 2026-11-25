// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.migrate1x.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.pgledger.application.admin.OwnerAuditWriter;
import org.pgledger.database.repository.CodeRevisionRepository;
import org.pgledger.database.repository.CommonCodeRepository;
import org.pgledger.database.repository.Counter;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ConflictException;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.LookupException;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationIssue;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.model.CommonCode;
import org.pgledger.domain.rule.CodeDirection;
import org.pgledger.domain.rule.CodeGroup;
import org.pgledger.domain.rule.OwnerAction;
import org.pgledger.domain.value.OwnerIdentity;
import org.pgledger.migrate1x.database.ColumnRule;
import org.pgledger.migrate1x.database.LegacyRow;
import org.pgledger.migrate1x.database.LegacySourceRepository;
import org.pgledger.migrate1x.database.LegacyTable;
import org.pgledger.migrate1x.database.LegacyTargetRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Copies the legacy ledger (the old schema {@code ledger}, read only) into the new schema, and checks the copy. The whole
 * import is one transaction of the new schema (the class is {@code @Transactional}), so a failure anywhere leaves no
 * imported row and a rerun can succeed; the legacy side is read in one read-only snapshot
 * ({@link LegacySnapshotService#read}) that the import never writes to.
 *
 * <p>Steps, in this order:
 * <ol>
 * <li>The new schema must hold only what {@code migrate} and {@code setup} made: no row in a table of ledger data, none in
 * {@code session_key}, and no earlier import (an {@code owner_audit} row of the operation {@code IMPORT}); the seed codes in
 * {@code common_code} and the audit rows of the operator actions that make no ledger data ({@code CODE_SET},
 * {@code DRIVER_REGISTERED}) are accepted, because the installer runs {@code setup} before the import
 * ({@link LegacyTargetRepository#tablesWithData}); otherwise {@link ConflictCode#DUPLICATE_KEY}, nothing is written
 * (MIG-009). A seed code that the legacy ledger also has is overwritten with the legacy values (the row of the same key),
 * and the comparison reads back only the keys of the legacy side.</li>
 * <li>The legacy schema must be complete (its installer steps, MIG-021); otherwise {@link LookupCode#NOT_FOUND}.</li>
 * <li>Validation pass: every legacy row is mapped ({@link LegacyRowMapper}); every row that cannot be imported as it is
 * (an empty text in a NOT NULL column that identifies a row, an id that is malformed or differs only in case from another of its
 * kind, a text longer than its column ...) is
 * collected, and when there is any the import stops with one {@link ValidationException} that lists them all
 * (MIG-023, MIG-028). Nothing is written, no value is invented. The numbers the legacy ledger used are collected in the
 * same pass.</li>
 * <li>Copy pass: the tables are written in the order of {@link LegacyTable} (defects with the merge target first), the
 * rows through the repository of the new schema; the counters of the new schema continue after the highest legacy ids
 * (MIG-007); the code revision is bumped so a running server reloads the common codes; the grants are copied without a
 * session key (the owner registers the key of each session, then the grant works).</li>
 * <li>Comparison: per table the number of rows and the SHA-256 digest of the canonical rows of the legacy side (as mapped)
 * and of the new schema (read back) must be equal (MIG-002, MIG-019); otherwise {@link InfrastructureCode#INTEGRITY_VIOLATION}
 * with the table, both counts and both digests, and the transaction rolls back.</li>
 * <li>One audit row {@link OwnerAction#IMPORT} names the actor.</li>
 * </ol>
 *
 * <p>The import is an operator command of the migration tool ({@code import-1x}); the service is imported by the database
 * configuration of that tool ({@link ImportServices}), and no other program has it.
 *
 * <p>{@link #verify()} runs the validation and the comparison alone, against what the new schema holds now.
 */
@Service
@Transactional
public class LegacyImportService {

    /** The surrogate key sequences whose counters continue after the highest legacy id. */
    private static final List<Counter> COPIED_COUNTERS = List.of(Counter.HISTORY, Counter.DELIVERABLE,
            Counter.ISSUE_CASE, Counter.SESSION_GRANT);

    private static final String VALIDATION_SUBJECT = "legacy data";
    private static final String MISSING_STEP = "legacy migration step";

    private final ObjectProvider<LegacySourceRepository> sources;
    private final LegacySnapshotService snapshot;
    private final LegacyTargetRepository target;
    private final IdAllocator ids;
    private final CodeRevisionRepository revision;
    private final OwnerAuditWriter audit;
    private final LegacyRowMapper mapper;
    private final CommonCodeRepository codes;

    public LegacyImportService(ObjectProvider<LegacySourceRepository> sources, LegacySnapshotService snapshot,
            LegacyTargetRepository target, IdAllocator ids, CodeRevisionRepository revision, OwnerAuditWriter audit,
            LegacyRowMapper mapper, CommonCodeRepository codes) {
        this.sources = sources;
        this.snapshot = snapshot;
        this.target = target;
        this.ids = ids;
        this.revision = revision;
        this.audit = audit;
        this.mapper = mapper;
        this.codes = codes;
    }

    /** True when the connection to the legacy ledger is configured ({@code ledger.import.source.url}). */
    @Transactional(propagation = Propagation.SUPPORTS)
    public boolean sourceConfigured() {
        return sources.getIfAvailable() != null;
    }

    /**
     * Imports the legacy ledger and checks the copy.
     *
     * @param actor who runs the import (the audit row names it)
     * @throws ConflictException DUPLICATE_KEY: the new schema already holds ledger data or an earlier import left its audit
     *         row (the tables are the argument)
     * @throws LookupException NOT_FOUND: the legacy schema lacks installer steps (they are the argument)
     * @throws ValidationException VALIDATION_FAILED: rows that cannot be imported as they are (they are the issues)
     * @throws InfrastructureException INTEGRITY_VIOLATION: a table of the new schema differs from the legacy table (the
     *         arguments are groups of five: table, legacy rows, new rows, legacy digest, new digest)
     */
    public ImportReport importLegacy(OwnerIdentity actor) {
        Objects.requireNonNull(actor, "actor");
        LegacySourceRepository source = requireSource();
        List<String> occupied = target.tablesWithData();
        if (!occupied.isEmpty()) {
            throw new ConflictException(ConflictCode.DUPLICATE_KEY,
                    List.of("ledger data in " + String.join(", ", occupied)));
        }
        return snapshot.read(() -> {
            requireSteps(source);
            Map<LegacyTable, Map<String, ColumnRule>> rules = columnRules();
            ImportCounters counters = new ImportCounters();
            refuse(validate(source, rules, counters));
            Map<LegacyTable, TableDigest> legacySide = pass(source, rules, counters, true, TableDigest::new);
            for (Counter counter : COPIED_COUNTERS) {
                ids.raise(counter, counters.highest(counter));
            }
            revision.bump();
            ImportReport report = withUnknownDirections(compare(source.schema(), legacySide), source);
            audit.record(actor, OwnerAction.IMPORT, "legacy schema " + source.schema(), report.detail());
            return report;
        });
    }

    /**
     * Compares the legacy ledger with what the new schema holds now, table by table, as the import does after the copy:
     * the same validation and the same count and digest per table; nothing is written.
     *
     * @throws InfrastructureException INTEGRITY_VIOLATION as for {@link #importLegacy}
     */
    @Transactional(readOnly = true)
    public ImportReport verify() {
        LegacySourceRepository source = requireSource();
        return snapshot.read(() -> {
            requireSteps(source);
            Map<LegacyTable, Map<String, ColumnRule>> rules = columnRules();
            ImportCounters counters = new ImportCounters();
            refuse(validate(source, rules, counters));
            return withUnknownDirections(compare(source.schema(), pass(source, rules, counters, false, TableDigest::new)),
                    source);
        });
    }

    /**
     * Checks that the new schema still holds what the legacy ledger held, after 2.0 has been used: what {@code retire-1x} asks
     * before it removes the legacy objects. {@link #verify()} cannot answer that once an author has written to the new ledger (it
     * counts the rows 2.0 added and compares the rows 2.0 edited and the counters it moved on); this one holds each table to what
     * 2.0 may do to its rows ({@link MovedComparison}): nothing is written. The legacy ledger is validated
     * first, as for {@link #verify()}.
     *
     * @throws InfrastructureException INTEGRITY_VIOLATION: a row of the legacy ledger is gone from the new schema, or a row
     *         that must be as imported is not (the arguments are as for {@link #importLegacy})
     */
    @Transactional(readOnly = true)
    public ImportReport verifyMoved() {
        LegacySourceRepository source = requireSource();
        return snapshot.read(() -> {
            requireSteps(source);
            Map<LegacyTable, Map<String, ColumnRule>> rules = columnRules();
            ImportCounters counters = new ImportCounters();
            refuse(validate(source, rules, counters));
            Map<LegacyTable, TableDigest> legacySide = pass(source, rules, counters, false, TableDigest::moved);
            MovedComparison moved = new MovedComparison(target);
            List<TableCheck> checks = new ArrayList<>();
            for (LegacyTable table : LegacyTable.values()) {
                moved.check(table, legacySide.get(table)).ifPresent(checks::add);
            }
            return conclude(source.schema(), checks);
        });
    }

    /**
     * The report with the relations whose direction is unknown. 1.x stored every relation with its smaller id first (the
     * history of a link names the same canonical order and its receipt keeps only a digest of the request), so which defect
     * the caller named first is not in the legacy data: a relation of a kind that the new ledger keeps with a direction
     * ({@code caused_by}, {@code blocks}) is imported as it is stored, never guessed, and listed for the owner to check.
     */
    private ImportReport withUnknownDirections(ImportReport report, LegacySourceRepository source) {
        Set<String> directed = new HashSet<>();
        for (CommonCode code : codes.catalog(CodeGroup.RELATION_KIND).entries()) {
            if (code.direction() == CodeDirection.GIVEN) {
                directed.add(code.code().value());
            }
        }
        List<String> unknown = new ArrayList<>();
        if (!directed.isEmpty()) {
            source.scan(LegacyTable.DEFECT_RELATION, row -> {
                if (directed.contains(row.text("kind"))) {
                    unknown.add(row.text("a") + "|" + row.text("b") + "|" + row.text("kind"));
                }
            });
        }
        Collections.sort(unknown);
        return new ImportReport(report.schema(), report.tables(), unknown);
    }

    private LegacySourceRepository requireSource() {
        LegacySourceRepository source = sources.getIfAvailable();
        if (source == null) {
            throw new IllegalStateException("the legacy source is not configured (ledger.import.source.url); "
                    + "the caller asks sourceConfigured() first");
        }
        return source;
    }

    private static void requireSteps(LegacySourceRepository source) {
        List<String> missing = source.missingSteps();
        if (!missing.isEmpty()) {
            throw new LookupException(LookupCode.NOT_FOUND, List.of(MISSING_STEP, String.join(", ", missing)));
        }
    }

    private Map<LegacyTable, Map<String, ColumnRule>> columnRules() {
        Map<LegacyTable, Map<String, ColumnRule>> rules = new EnumMap<>(LegacyTable.class);
        for (LegacyTable table : LegacyTable.values()) {
            rules.put(table, target.columnRules(table));
        }
        return rules;
    }

    /** The validation pass: every row of every table is mapped, nothing is written; the numbers in use are collected. */
    private List<LegacyViolation> validate(LegacySourceRepository source,
            Map<LegacyTable, Map<String, ColumnRule>> rules, ImportCounters counters) {
        List<LegacyViolation> violations = new ArrayList<>();
        for (LegacyTable table : LegacyTable.values()) {
            List<LegacyRow> defects = new ArrayList<>();
            // the ids of a task or a condition that were seen, by their upper-case key: a second id with the same key differs
            // from the first only in case, and the new schema refuses that (its key column is UNIQUE, REH3-IMP-02)
            Map<String, String> idsByKey = new HashMap<>();
            source.scan(table, row -> {
                counters.collect(table, row);
                LegacyRowMapper.Mapped mapped = mapper.map(table, row, rules.get(table), counters);
                violations.addAll(mapped.violations());
                if (table == LegacyTable.DEFECT) {
                    defects.add(mapped.row());
                }
                if (table == LegacyTable.TASK || table == LegacyTable.RELEASE_CONDITION) {
                    String id = mapped.row().text("id");
                    String key = mapped.row().text("id_key");
                    String first = id == null ? null : idsByKey.putIfAbsent(key, id);
                    if (first != null && !Objects.equals(first, id)) {
                        violations.add(new LegacyViolation(table.targetName(), id, "id",
                                ValidationReason.ID_DIFFERS_ONLY_IN_CASE, List.of(first)));
                    }
                }
            });
            if (table == LegacyTable.DEFECT) {
                for (String id : DefectOrder.of(defects).loops()) {
                    violations.add(new LegacyViolation(table.targetName(), id, "merged_into",
                            ValidationReason.MERGE_CHAIN_LOOPS, List.of()));
                }
            }
        }
        return violations;
    }

    private static void refuse(List<LegacyViolation> violations) {
        if (!violations.isEmpty()) {
            List<ValidationIssue> issues = violations.stream().map(LegacyViolation::toIssue).toList();
            throw new ValidationException(ValidationCode.VALIDATION_FAILED, List.of(VALIDATION_SUBJECT), issues,
                    Map.of());
        }
    }

    /**
     * The pass over the legacy tables that makes the legacy side of the comparison: every row is mapped to the row of the
     * new schema, added to the digest of its table and, when {@code write} is set, written.
     */
    private Map<LegacyTable, TableDigest> pass(LegacySourceRepository source,
            Map<LegacyTable, Map<String, ColumnRule>> rules, ImportCounters counters, boolean write,
            Function<LegacyTable, TableDigest> digestOf) {
        Map<LegacyTable, TableDigest> digests = new EnumMap<>(LegacyTable.class);
        for (LegacyTable table : LegacyTable.values()) {
            TableDigest digest = digestOf.apply(table);
            digests.put(table, digest);
            Map<String, ColumnRule> tableRules = rules.get(table);
            if (table == LegacyTable.DEFECT) {
                List<LegacyRow> defects = new ArrayList<>();
                source.scan(table, row -> defects.add(mapper.map(table, row, tableRules, counters).row()));
                for (LegacyRow row : DefectOrder.of(defects).ordered()) {
                    digest.add(row);
                    if (write) {
                        target.insert(table, row);
                    }
                }
            } else {
                source.scan(table, row -> {
                    LegacyRow mapped = mapper.map(table, row, tableRules, counters).row();
                    digest.add(mapped);
                    if (write) {
                        store(table, mapped);
                    }
                });
            }
        }
        return digests;
    }

    private void store(LegacyTable table, LegacyRow row) {
        if (table == LegacyTable.COMMON_CODE) {
            target.putCommonCode(row);
        } else {
            target.insert(table, row);
        }
    }

    /** Reads every table of the new schema back and compares it with the legacy side. */
    private ImportReport compare(String schema, Map<LegacyTable, TableDigest> legacySide) {
        List<TableCheck> checks = new ArrayList<>();
        for (LegacyTable table : LegacyTable.values()) {
            TableDigest legacy = legacySide.get(table);
            TableDigest current = new TableDigest(table);
            if (table.targetMayHoldOtherRows()) {
                target.scan(table, row -> current.addIfKeyed(row, legacy.keys()));
            } else {
                target.scan(table, current::add);
            }
            checks.add(new TableCheck(table, legacy.count(), current.count(), legacy.digest(), current.digest()));
        }
        return conclude(schema, checks);
    }

    /** The report of the checks when every table matched, else the refusal that names every table that did not. */
    private static ImportReport conclude(String schema, List<TableCheck> checks) {
        List<String> differences = new ArrayList<>();
        for (TableCheck check : checks) {
            if (!check.matches()) {
                differences.addAll(List.of(check.table().targetName(), String.valueOf(check.sourceRows()),
                        String.valueOf(check.targetRows()), check.sourceDigest().hex(), check.targetDigest().hex()));
            }
        }
        if (!differences.isEmpty()) {
            throw new InfrastructureException(InfrastructureCode.INTEGRITY_VIOLATION, differences);
        }
        return new ImportReport(schema, checks);
    }
}
