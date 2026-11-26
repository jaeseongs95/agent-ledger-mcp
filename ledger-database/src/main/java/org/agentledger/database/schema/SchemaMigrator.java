// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.database.schema;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.agentledger.database.repository.DatabaseEncodingRepository;
import org.agentledger.database.repository.DatabaseEncodingRepository.EncodingProbe;
import org.agentledger.database.repository.SchemaHistoryRepository;
import org.agentledger.database.repository.SchemaHistoryRepository.Outcome;
import org.agentledger.database.repository.SchemaHistoryRepository.Row;
import org.agentledger.database.repository.SqlBuilder;
import org.agentledger.domain.error.InfrastructureCode;
import org.agentledger.domain.error.InfrastructureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/**
 * Applies the ANSI schema files {@code V{n}__*.sql} in order and records every attempt as a row of {@code schema_history}, in the
 * form that the three products share (the common migration applier, edition 2). It is the run of the operator's {@code migrate}
 * command; the applications only verify (see {@link SchemaVerifier}) and refuse to start on a schema that is not up to date,
 * naming {@code ledger-cli migrate}. The run itself opens no transaction: it loops over the files and calls the service
 * {@link SchemaFileApplier} once per file, whose method is one attempt and one transaction (principle 4.4); the history rows that
 * are written outside such a transaction (a FAILED attempt, the table itself) are single statements of the repository.
 *
 * <ul>
 * <li>A version is applied once. Its attempt inserts its own row {@code (version, attempt)} as FAILED first and turns that one row
 *     to SUCCEEDED last; the file's SHA-256 is recorded. If the file changes afterwards the run stops before it applies anything
 *     and adds no row (a changed file is no attempt).
 * <li>A file that fails is rolled back and recorded as a FAILED row of its attempt, unless the claim row of the attempt was kept
 *     (a DBMS that commits every DDL statement at once); migration stops. The next run retries that version as the next attempt
 *     and keeps the earlier rows: no row is updated but the own row of an attempt, none is deleted.
 * <li>Two runners that apply the same version at once claim the same {@code (version, attempt)}: the second waits for the first in
 *     the database and then gets the duplicate key. It reads the history once: the version SUCCEEDED with the digest of its
 *     file means the other runner did it, so it goes on; otherwise it stops with {@code SCHEMA_MIGRATION_FAILED} and the next run
 *     tries again. The runners themselves never wait, poll or retry.
 * <li>Gaps, files applied out of order and files that are missing are refused. The encoding of the database has to be UTF-8, and
 *     the long text type of the files is the one the dialect of {@link SqlBuilder} names (edition 4).
 * </ul>
 *
 * <p>Why this applier and why the explicit command. The ledger does not use Flyway: a Flyway release from 10 on needs a module for
 * every DBMS it supports and answers "Unsupported Database" for one it has none for, while the ledger asks for ANSI SQL on any
 * database whose JDBC driver the owner registers (the three differences, the row limit, the long text type and the encoding probe, are decided by the dialect of SqlBuilder). And the ledger
 * migrates by the explicit command {@code ledger-cli migrate} and not when the server starts, because the server never holds the
 * password of the DDL account: the schema is changed by the DDL account of the operator, the servers run on the application account
 * that can read and write the tables and cannot change them, so a server that is taken over cannot change the schema.
 *
 * <p>What the history says about a file is {@link SchemaHistory}, the same judgement the verifier uses. Failures are an
 * {@link InfrastructureException} with a {@code SCHEMA_*} code.
 */
@Component
public class SchemaMigrator {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaMigrator.class);

    private final SchemaHistoryRepository repository;
    private final SchemaFileApplier applier;
    private final ResourcePatternResolver resolver;
    private final SchemaProperties properties;
    private final Clock clock;
    private final SqlBuilder sqlBuilder;
    private final DatabaseEncodingRepository encodings;

    public SchemaMigrator(SchemaHistoryRepository repository, SchemaFileApplier applier,
            ResourcePatternResolver resolver, SchemaProperties properties, Clock clock, SqlBuilder sqlBuilder,
            DatabaseEncodingRepository encodings) {
        this.sqlBuilder = sqlBuilder;
        this.encodings = encodings;
        this.repository = repository;
        this.applier = applier;
        this.resolver = resolver;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Applies every pending version and returns the versions applied by this call (empty when up to date, and for a version that
     * another runner applied at the same time).
     *
     * @throws InfrastructureException one of the schema codes ({@code SCHEMA_*}) when the files and the history disagree,
     *         a file failed or the database cannot be reached
     */
    public List<Integer> migrate() {
        List<MigrationFile> files = MigrationFile.load(resolver, properties.location());
        MigrationFile.requireContiguous(files);
        try {
            // what the placeholder of the files stands for on this database: the dialect of SqlBuilder (from the product name
            // of the connection) decides, asked once; no setting. It is asked before any statement that changes the database,
            // so a product without a dialect is refused (DATABASE_PRODUCT_UNSUPPORTED) before a schema or a table is made.
            String longTextType = sqlBuilder.longTextType();
            requireUtf8Database();
            if (properties.name() != null) {
                // the data source of the application already names this schema for every connection (DriverDataSources)
                ensureSchema(properties.name());
            }
            ensureTable();
            List<Integer> applied = new ArrayList<>();
            for (MigrationFile file : files) {
                SchemaHistory history = new SchemaHistory(repository.rows());
                history.requireFileForEveryVersion(files);
                if (!settle(history, file) && apply(file, history.nextAttemptOf(file.version()), longTextType)) {
                    applied.add(file.version());
                }
            }
            LOG.info("schema migration finished: applied={} total={}", applied, files.size());
            return applied;
        } catch (CannotGetJdbcConnectionException e) {
            throw new InfrastructureException(InfrastructureCode.SCHEMA_DATABASE_UNREACHABLE, List.of(), e);
        }
    }

    /**
     * Proves that the database stores UTF-8 before anything is changed: the probe text goes through the probe query of the dialect and
     * has to come back whole ({@link DatabaseEncoding#requireUtf8}); a product that also keeps a default character set for its schemas
     * has that one proved too ({@link DatabaseEncoding#requireUtf8Schema}). The queries are the dialect's ({@link SqlBuilder}); no
     * name of a character set is compared here. A probe query that fails with a data exception is refused as well
     * ({@link DatabaseEncoding#probeFailure}).
     */
    private void requireUtf8Database() {
        try {
            EncodingProbe probe = encodings.encodingProbe();
            DatabaseEncoding.requireUtf8(sqlBuilder.countsCodePoints(), probe.length(), probe.value());
            encodings.schemaCharsets().ifPresent(DatabaseEncoding::requireUtf8Schema);
        } catch (DataAccessException e) {
            // a data exception (SQLSTATE class 22) of a probe query is the database saying that it cannot store the text: refused as
            // an encoding problem; any other failure is thrown as it is
            throw DatabaseEncoding.probeFailure(e);
        }
    }

    /**
     * Creates the schema when it is missing. A second runner may create it first: when the creation fails the schema is looked for
     * once, and found means go on (the other runner made it), not found means the failure is thrown as it is.
     */
    private void ensureSchema(String schema) {
        try {
            repository.createSchemaIfMissing(schema);
        } catch (DataAccessException e) {
            if (!repository.schemaExists(schema)) {
                throw e;
            }
            LOG.info("the schema {} was created by another migration process at the same time", schema, e);
        }
    }

    /** The same for the history table: when the creation fails it is looked for once; found means go on, else the failure. */
    private void ensureTable() {
        if (repository.tableExists()) {
            return;
        }
        try {
            repository.createTable();
        } catch (DataAccessException e) {
            if (!repository.tableExists()) {
                throw new InfrastructureException(InfrastructureCode.SCHEMA_HISTORY_NOT_CREATED, List.of(), e);
            }
            LOG.info("schema_history was created by another migration process at the same time", e);
        }
    }

    /**
     * Looks at the history of one version. Returns true when it is applied and intact; false when it must be
     * applied (never succeeded, or only failed attempts so far); throws when it must stop. A changed file stops the run and
     * records nothing.
     */
    private boolean settle(SchemaHistory history, MigrationFile file) {
        return switch (history.verdictOf(file)) {
            case PENDING, FAILED_ONLY -> {
                history.requireNothingLaterApplied(file);
                yield false;
            }
            case DIGEST_CHANGED -> throw history.digestMismatch(file);
            case LATEST_FAILED -> throw SchemaHistory.recordedFailed(file);
            case INTACT -> true;
        };
    }

    /**
     * Has the service run one attempt of one file, claim and SUCCEEDED row included (one transaction); true when applied by this
     * call. A failure rolls the transaction back; the FAILED row is written afterwards, outside it.
     */
    private boolean apply(MigrationFile file, int attempt, String longTextType) {
        try {
            applier.apply(file, attempt, now(), longTextType);
            LOG.info("applied V{} {} (attempt {})", file.version(), file.fileName(), attempt);
            return true;
        } catch (DataAccessException | TransactionException e) {
            return failed(file, attempt, e);
        }
    }

    /**
     * The attempt did not end. The history is read once. The duplicate key of the claim means that another runner has this
     * attempt: when it is the same version with the digest of this file, SUCCEEDED, it is applied and this runner goes on (false: not
     * applied by this call). Otherwise the attempt is recorded as FAILED unless its row is there already (the claim that a DBMS with
     * committing DDL kept, or the row of the runner that has the attempt) and the failure is thrown; the next run tries again.
     *
     * <p>Catching the duplicate key here is not what principle 4.4 forbids: this is the migration runner, not a use case, the catch
     * is outside the transaction of the attempt (which is rolled back), and it only reads the history once and then skips or stops,
     * as the common format (edition 2, section 3) prescribes.
     */
    private boolean failed(MigrationFile file, int attempt, RuntimeException cause) {
        InfrastructureException failure = new InfrastructureException(InfrastructureCode.SCHEMA_MIGRATION_FAILED,
                List.of(String.valueOf(file.version()), file.fileName()), cause);
        try {
            SchemaHistory now = new SchemaHistory(repository.rows());
            if (cause instanceof DuplicateKeyException && now.isAppliedWithDigestOf(file)) {
                LOG.info("V{} was applied by another migration process", file.version(), cause);
                return false;
            }
            if (now.attemptsOf(file.version()).stream().noneMatch(row -> row.attempt() == attempt)) {
                repository.append(new Row(file.version(), attempt, file.fileName(), file.digest(), now(), Outcome.FAILED));
            }
        } catch (DataAccessException recording) {
            // the history cannot be read or written either: the failure of the file is still what the caller must learn,
            // so it stays the cause, and the failure of recording it is attached, not thrown in its place
            failure.addSuppressed(recording);
        }
        throw failure;
    }

    /** Now, to the microsecond (what the column holds). */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
