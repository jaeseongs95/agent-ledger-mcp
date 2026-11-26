// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.migrate1x.service;

import java.util.function.Supplier;
import org.veridger.migrate1x.database.LegacySourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The boundary of the read side of the legacy import (principle 4.4): one read-only snapshot of the legacy database, isolation
 * REPEATABLE READ, so the rows the validation pass saw are the rows the copy pass and the comparison see, and nothing is ever
 * written to the legacy database. The method is {@code @Transactional} on the transaction manager of the legacy data source
 * ({@link LegacySourceRepository#TRANSACTION_MANAGER}); {@link LegacySourceRepository} joins it.
 *
 * <p>Why this is a service of its own and not a second annotation on {@link LegacyImportService}: the import is one use case
 * over two databases, which are two transaction resources, and a method carries one transaction manager. The new schema's
 * transaction is the import service's own (the whole import is one transaction there), and the snapshot of the legacy ledger
 * is opened inside it, on its own manager; Spring has no manager that spans two data sources. The snapshot is therefore a
 * call through the proxy of this bean, made once per import, and is not a way around the self-invocation of the import service.
 *
 * <p>Like the import service it is imported by the database configuration of the tool ({@link ImportServices}) and exists only
 * where the tool has its database.
 */
@Service
@Transactional(transactionManager = LegacySourceRepository.TRANSACTION_MANAGER, readOnly = true,
        isolation = Isolation.REPEATABLE_READ)
public class LegacySnapshotService {

    /** Runs the work in one read-only snapshot of the legacy database; it ends when the work returns or throws. */
    public <T> T read(Supplier<T> work) {
        return work.get();
    }
}
