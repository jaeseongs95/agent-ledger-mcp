// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.migrate1x.service;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.veridger.domain.error.InfrastructureException;
import org.veridger.domain.error.LookupException;
import org.veridger.domain.error.ValidationException;
import org.veridger.domain.value.Sha256;
import org.veridger.migrate1x.database.LegacyInventory;
import org.veridger.migrate1x.database.LegacySchemaRepository;
import org.veridger.migrate1x.database.LegacySourceRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes what the 1.x installer made in the database (functions, triggers, views, tables, the schema and its roles) after the
 * move to 2.0 was accepted. One use case, one service method, one transaction: the transaction is the one of the legacy data
 * source ({@link LegacySourceRepository#TRANSACTION_MANAGER}), where the drops are made; PostgreSQL DDL is transactional, so a
 * failure leaves everything in place. The ledger of 2.0 is only read (by {@link LegacyImportService#verifyMoved}, which opens its
 * own transaction on the ledger). The check reads the legacy ledger through {@link LegacySnapshotService}, which joins the
 * transaction of this service, so a joined transaction has the isolation of the one that opened it: both methods are
 * REPEATABLE READ, the isolation the snapshot declares (the transaction manager cannot check a join: the check of Spring reads the
 * transaction of the ledger, which the verification opens in between, so a test holds it: {@code LegacyRetireAfterUseTest}).
 *
 * <p>Both operations check the same preconditions first, in this order, and refuse with {@link RetireRefusedException} or with
 * the {@code LedgerException} of the verification:
 * <ol>
 * <li>the legacy schema is not the schema of 2.0 ({@link RetireRefusal#OWN_SCHEMA}: it holds a {@code schema_history});</li>
 * <li>the ledger of 2.0 still holds what the legacy ledger holds ({@link LegacyImportService#verifyMoved}: after 2.0 has been
 * used it has rows, versions and counters of its own, so each table is held to what 2.0 may do to its rows; a mismatch is
 * {@code INTEGRITY_VIOLATION}), so what is removed has been moved;</li>
 * <li>no other session runs as a 1.x role or uses the schema ({@link RetireRefusal#SESSIONS_CONNECTED});</li>
 * <li>the account owns the objects it is to drop ({@link RetireRefusal#NOT_OWNER}).</li>
 * </ol>
 * {@link #plan} then answers with the list and its digest and writes nothing; {@link #retire} recomputes the list and drops it only
 * when its digest is the one that was confirmed ({@link RetireRefusal#SOURCE_CHANGED} otherwise). Nothing runs by itself: the
 * command of the owner is the only caller.
 *
 * <p>Like the import it is imported by the database configuration of the tool ({@link ImportServices}) and exists only there.
 */
@Service
@Transactional(transactionManager = LegacySourceRepository.TRANSACTION_MANAGER, isolation = Isolation.REPEATABLE_READ)
public class LegacyRetireService {

    /** The table of the migration bookkeeping of 2.0 ({@code schema_history}); a schema that holds it is the ledger's own. */
    private static final String LEDGER_2_TABLE = "schema_history";

    private final ObjectProvider<LegacySchemaRepository> schemas;
    private final LegacyImportService importer;

    public LegacyRetireService(ObjectProvider<LegacySchemaRepository> schemas, LegacyImportService importer) {
        this.schemas = schemas;
        this.importer = importer;
    }

    /**
     * The list of what would be removed, with its digest. Writes nothing.
     *
     * @param protectedAccounts the owner account and the application account of the 2.0 settings: a role of that name stays
     * @throws RetireRefusedException a precondition does not hold
     * @throws InfrastructureException INTEGRITY_VIOLATION: the ledger of 2.0 differs from the legacy ledger (as for the import)
     * @throws LookupException NOT_FOUND: the legacy schema lacks installer steps
     * @throws ValidationException VALIDATION_FAILED: legacy rows that cannot be imported as they are
     */
    @Transactional(transactionManager = LegacySourceRepository.TRANSACTION_MANAGER, readOnly = true,
            isolation = Isolation.REPEATABLE_READ)
    public LegacyRetirePlan plan(Set<String> protectedAccounts) {
        return prepare(requireRepository(), protectedAccounts);
    }

    /**
     * Drops what {@link #plan} lists, when the list is the confirmed one, in one transaction.
     *
     * @param confirmed         the digest of the list the owner read
     * @param protectedAccounts as for {@link #plan}
     * @return the list that was dropped
     * @throws RetireRefusedException a precondition does not hold, or the digest is not the one of the list now (SOURCE_CHANGED;
     *         the argument is the digest now)
     */
    public LegacyRetirePlan retire(Sha256 confirmed, Set<String> protectedAccounts) {
        Objects.requireNonNull(confirmed, "confirmed");
        LegacySchemaRepository repository = requireRepository();
        LegacyRetirePlan plan = prepare(repository, protectedAccounts);
        if (!Objects.equals(plan.digest(), confirmed)) {
            throw new RetireRefusedException(RetireRefusal.SOURCE_CHANGED, List.of(plan.digest().hex()));
        }
        repository.drop(plan.objects());
        return plan;
    }

    private LegacyRetirePlan prepare(LegacySchemaRepository repository, Set<String> protectedAccounts) {
        if (repository.holdsTable(LEDGER_2_TABLE)) {
            throw new RetireRefusedException(RetireRefusal.OWN_SCHEMA, List.of(repository.schema()));
        }
        importer.verifyMoved();
        LegacyInventory inventory = repository.inventory(protectedAccounts);
        if (!inventory.sessions().isEmpty()) {
            throw new RetireRefusedException(RetireRefusal.SESSIONS_CONNECTED, inventory.sessions());
        }
        if (!inventory.notOwned().isEmpty()) {
            throw new RetireRefusedException(RetireRefusal.NOT_OWNER, inventory.notOwned());
        }
        return new LegacyRetirePlan(repository.schema(), inventory.objects(), inventory.skippedRoles());
    }

    private LegacySchemaRepository requireRepository() {
        return schemas.getIfAvailable(() -> {
            throw new IllegalStateException("the legacy source is not configured (ledger.import.source.url); "
                    + "the caller asks LegacyImportService.sourceConfigured() first");
        });
    }
}
