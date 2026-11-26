// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.repository;

import org.springframework.data.jdbc.core.JdbcAggregateOperations;

/** Spring Data fragment implementation of {@link Insertable}. */
public class InsertableImpl<T> implements Insertable<T> {

    private final JdbcAggregateOperations operations;

    public InsertableImpl(JdbcAggregateOperations operations) {
        this.operations = operations;
    }

    @Override
    public T insert(T aggregate) {
        return operations.insert(aggregate);
    }
}
