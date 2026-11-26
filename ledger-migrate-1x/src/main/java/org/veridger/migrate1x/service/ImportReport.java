// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.migrate1x.service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * What a legacy import (or a re-verification) found: the comparison of every table, all of which matched, or the call
 * would have been refused, and the relations whose direction the import could not restore.
 *
 * @param schema the legacy schema that was read
 * @param tables one comparison per table, in the order the tables are copied
 * @param relationsOfUnknownDirection the ids ({@code a|b|kind}, in the order they are stored) of the imported relations
 *        whose kind has a direction in the new ledger (for example {@code caused_by}): 1.x stored every relation with its
 *        smaller id first and kept nowhere which defect the caller named first, so the stored order may be the wrong way
 *        round and the owner has to check these relations; sorted, empty when there are none
 */
public record ImportReport(String schema, List<TableCheck> tables, List<String> relationsOfUnknownDirection) {

    public ImportReport {
        tables = List.copyOf(tables);
        relationsOfUnknownDirection = List.copyOf(relationsOfUnknownDirection);
    }

    /** A report without relations of unknown direction. */
    public ImportReport(String schema, List<TableCheck> tables) {
        this(schema, tables, List.of());
    }

    /** The rows of all tables. */
    public long rows() {
        return tables.stream().mapToLong(TableCheck::targetRows).sum();
    }

    /**
     * The report as one line for the owner's audit trail: the rows of all tables and of each one, and the number of relations
     * of unknown direction when there are some.
     */
    public String detail() {
        String detail = "rows=" + rows() + " " + tables.stream()
                .map(check -> check.table().targetName() + "=" + check.targetRows()).collect(Collectors.joining(", "));
        return relationsOfUnknownDirection.isEmpty() ? detail
                : detail + " relations_of_unknown_direction=" + relationsOfUnknownDirection.size();
    }
}
