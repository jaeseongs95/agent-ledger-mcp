// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.migrate1x.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.pgledger.domain.value.Sha256;
import org.pgledger.migrate1x.database.LegacyColumn;
import org.pgledger.migrate1x.database.LegacyRow;
import org.pgledger.migrate1x.database.LegacyTable;

/**
 * The count and the digest of the rows of one table, collected row by row. The digest is the SHA-256 over the lines
 * {@code <canonical key> TAB <digest of the canonical row> LF}, ordered by the canonical key of the new table (a plain
 * string comparison, so no collation of any DBMS takes part and the two sides order alike). Only the key and the
 * 64-character digest of a row are kept, not the row. The digest of a row covers the columns it is made for: all columns of the
 * new table, or, for the check after 2.0 was used ({@link #moved}), those that 2.0 does not move on by itself.
 */
final class TableDigest {

    private record Entry(String key, String rowDigest) {
    }

    private static final String VERSION = "version";

    private final LegacyTable table;
    private final List<LegacyColumn> compared;
    private final List<LegacyColumn> keyColumns;
    private final boolean versioned;
    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> keys = new HashSet<>();
    private final Map<String, Long> versions = new HashMap<>();

    /** A digest over all columns of the new table. */
    TableDigest(LegacyTable table) {
        this(table, table.targetColumns());
    }

    private TableDigest(LegacyTable table, List<LegacyColumn> compared) {
        this.table = table;
        this.compared = compared;
        this.keyColumns = table.targetColumns().stream().filter(column -> table.targetKey().contains(column.name()))
                .toList();
        this.versioned = table.targetColumns().stream().anyMatch(column -> column.name().equals(VERSION));
    }

    /** A digest over the columns that a check after 2.0 was used compares ({@link LegacyTable#comparedColumns}). */
    static TableDigest moved(LegacyTable table) {
        return new TableDigest(table, table.comparedColumns());
    }

    /** The canonical key of a row of the new table's shape. */
    String keyOf(LegacyRow row) {
        return CanonicalRow.of(keyColumns, row);
    }

    /** Adds a row of the new table's shape. */
    void add(LegacyRow row) {
        String key = keyOf(row);
        entries.add(new Entry(key, CanonicalRow.digest(compared, row)));
        keys.add(key);
        if (versioned) {
            versions.put(key, (Long) row.get(VERSION));
        }
    }

    /** Adds the row only when its key is one of {@code only}; the common codes are compared by the keys of the legacy rows. */
    void addIfKeyed(LegacyRow row, Set<String> only) {
        if (only.contains(keyOf(row))) {
            add(row);
        }
    }

    long count() {
        return entries.size();
    }

    /** The count of the rows whose key is not one of {@code skipped}. */
    long countExcept(Set<String> skipped) {
        return entries.stream().filter(entry -> !skipped.contains(entry.key())).count();
    }

    /** The canonical keys of the rows added. */
    Set<String> keys() {
        return keys;
    }

    /** The version of the row with the key, null when the table has no version or the key is not here. */
    Long versionOf(String key) {
        return versions.get(key);
    }

    Sha256 digest() {
        return digestExcept(Set.of());
    }

    /** The digest of the rows whose key is not one of {@code skipped}. */
    Sha256 digestExcept(Set<String> skipped) {
        List<Entry> ordered = new ArrayList<>(entries.stream().filter(entry -> !skipped.contains(entry.key())).toList());
        ordered.sort(Comparator.comparing(Entry::key));
        StringBuilder lines = new StringBuilder();
        for (Entry entry : ordered) {
            lines.append(entry.key()).append('\t').append(entry.rowDigest()).append('\n');
        }
        return Sha256.ofText(lines.toString());
    }

    /** The digest of a set of keys alone (a table whose rows only have to exist): the lines {@code <key> LF} in key order. */
    static Sha256 digestOfKeys(Collection<String> keys) {
        StringBuilder lines = new StringBuilder();
        for (String key : new TreeSet<>(keys)) {
            lines.append(key).append('\n');
        }
        return Sha256.ofText(lines.toString());
    }
}
