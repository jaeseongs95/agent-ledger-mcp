// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.migrate1x.service;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.agentledger.migrate1x.database.LegacyRow;
import org.agentledger.migrate1x.database.LegacyTable;
import org.agentledger.migrate1x.database.LegacyTargetRepository;

/**
 * The comparison of the new schema with the legacy ledger after 2.0 has been used: what {@code retire-1x} needs to know before
 * it removes the legacy objects, which is that everything the legacy ledger held was moved and is still there. The strict
 * comparison of the import ({@link LegacyImportService#verify}) cannot answer that once an author has written to the new ledger:
 * it counts the rows 2.0 added, it compares rows 2.0 edited, and it compares the counters that 2.0 moves on by use.
 *
 * <p>What a table is held to depends on what 2.0 may do to its rows ({@link LegacyTable.After}), which the grants of the
 * application account fix: a row that 2.0 never changes must be as imported, a row that 2.0 changes only by raising its version
 * must be as imported while it is at the imported version and must exist after that, a row that 2.0 changes in any way has to
 * exist, and a link that 2.0 may delete is not looked at. The rows that only the new schema has are never looked at.
 *
 * <p>The result per table is a {@link TableCheck} like that of the import, so a difference is reported the same way (the table,
 * both counts, both digests); where a table is held to the existence of its rows, the digests are those of the keys.
 */
final class MovedComparison {

    private final LegacyTargetRepository target;

    MovedComparison(LegacyTargetRepository target) {
        this.target = target;
    }

    /** The check of one table against the legacy side, empty for a table that 2.0 may have removed rows from. */
    Optional<TableCheck> check(LegacyTable table, TableDigest legacy) {
        return switch (table.after()) {
            case UNCHANGED -> Optional.of(unchanged(table, legacy));
            case VERSIONED -> Optional.of(versioned(table, legacy));
            case EDITED -> Optional.of(existing(table, legacy));
            case REMOVABLE -> Optional.empty();
        };
    }

    private TableCheck unchanged(LegacyTable table, TableDigest legacy) {
        TableDigest current = TableDigest.moved(table);
        target.scan(table, row -> current.addIfKeyed(row, legacy.keys()));
        return new TableCheck(table, legacy.count(), current.count(), legacy.digest(), current.digest());
    }

    private TableCheck versioned(LegacyTable table, TableDigest legacy) {
        TableDigest current = TableDigest.moved(table);
        Set<String> edited = new HashSet<>();
        target.scan(table, row -> {
            String key = current.keyOf(row);
            if (!legacy.keys().contains(key)) {
                return;
            }
            if (isEdited(row, legacy.versionOf(key))) {
                edited.add(key);
            } else {
                current.add(row);
            }
        });
        return new TableCheck(table, legacy.countExcept(edited), current.count(), legacy.digestExcept(edited),
                current.digest());
    }

    private TableCheck existing(LegacyTable table, TableDigest legacy) {
        TableDigest keyer = TableDigest.moved(table);
        Set<String> found = new TreeSet<>();
        target.scan(table, row -> {
            String key = keyer.keyOf(row);
            if (legacy.keys().contains(key)) {
                found.add(key);
            }
        });
        return new TableCheck(table, legacy.keys().size(), found.size(), TableDigest.digestOfKeys(legacy.keys()),
                TableDigest.digestOfKeys(found));
    }

    /** True when 2.0 wrote the row since the import: its version is higher than the imported one. */
    private static boolean isEdited(LegacyRow row, Long importedVersion) {
        return importedVersion != null && row.get("version") instanceof Long version && version > importedVersion;
    }
}
