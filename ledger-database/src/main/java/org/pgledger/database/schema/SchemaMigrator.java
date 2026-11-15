package org.pgledger.database.schema;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.pgledger.database.repository.SchemaHistoryRepository;
import org.pgledger.database.repository.SchemaHistoryRepository.Outcome;
import org.pgledger.database.repository.SchemaHistoryRepository.Row;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/**
 * Applies the ANSI schema files {@code V{n}__*.sql} in order and records every attempt as a row of {@code schema_history}
 * (rows are only appended). It is the run of the operator's {@code migrate} command; the applications only verify (see
 * {@link SchemaVerifier}). The run itself opens no transaction: it loops over the files and calls the service
 * {@link SchemaFileApplier} once per file, whose method is one transaction (principle 4.4); the history rows that are written
 * outside such a transaction (a FAILED attempt, the table itself) are single statements of the repository.
 *
 * <ul>
 * <li>A version is applied once. Its file's SHA-256 is recorded; if the file changes afterwards, the digest
 *     mismatch is recorded as a FAILED attempt and migration stops.
 * <li>A file that fails is recorded as FAILED and migration stops; the next run retries that version and appends
 *     another row. The latest outcome of every version must be SUCCESS before the next version is applied. On a
 *     DBMS with transactional DDL a failed file is rolled back as a whole; on others the part that ran stays and
 *     the operator repairs it before retrying.
 * <li>Gaps, files applied out of order and files that are missing are refused.
 * </ul>
 *
 * <p>What the history says about a file is {@link SchemaHistory}, the same judgement the verifier uses. Failures are an
 * {@link InfrastructureException} with a {@code SCHEMA_*} code.
 */
@Component
public class SchemaMigrator {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaMigrator.class);
    /**
     * How often the history is looked at while waiting for another migration process (see
     * {@link #appliedByAnotherProcess}). There is no event to wait for: the other process is a separate program, and the
     * only thing it leaves behind that this one can see is its row in {@code schema_history}. So the row is polled, at
     * this interval, until {@code ledger.schema.peer-wait} is over.
     */
    private static final long PEER_POLL_MILLIS = 50;

    private final SchemaHistoryRepository repository;
    private final SchemaFileApplier applier;
    private final ResourcePatternResolver resolver;
    private final SchemaProperties properties;
    private final Clock clock;

    public SchemaMigrator(SchemaHistoryRepository repository, SchemaFileApplier applier,
            ResourcePatternResolver resolver, SchemaProperties properties, Clock clock) {
        this.repository = repository;
        this.applier = applier;
        this.resolver = resolver;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Applies every pending version and returns the versions applied by this call (empty when up to date).
     *
     * @throws InfrastructureException one of the schema codes ({@code SCHEMA_*}) when the files and the history disagree,
     *         a file failed or the database cannot be reached
     */
    public List<Integer> migrate() {
        List<MigrationFile> files = MigrationFile.load(resolver, properties.location());
        MigrationFile.requireContiguous(files);
        try {
            if (properties.name() != null) {
                // the data source of the application already names this schema for every connection (DriverDataSources)
                repository.createSchemaIfMissing(properties.name());
            }
            ensureTable();
            List<Integer> applied = new ArrayList<>();
            for (MigrationFile file : files) {
                SchemaHistory history = new SchemaHistory(repository.rows());
                history.requireFileForEveryVersion(files);
                if (!settle(history, file) && apply(file)) {
                    applied.add(file.version());
                }
            }
            LOG.info("schema migration finished: applied={} total={}", applied, files.size());
            return applied;
        } catch (CannotGetJdbcConnectionException e) {
            throw new InfrastructureException(InfrastructureCode.SCHEMA_DATABASE_UNREACHABLE, List.of(), e);
        }
    }

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
     * applied (never succeeded, or only failed attempts so far); throws when it must stop.
     */
    private boolean settle(SchemaHistory history, MigrationFile file) {
        return switch (history.verdictOf(file)) {
            case PENDING, FAILED_ONLY -> {
                history.requireNothingLaterApplied(file);
                yield false;
            }
            case DIGEST_CHANGED -> {
                if (!history.latestFailureIsOf(file)) {
                    repository.append(new Row(file.version(), file.fileName(), file.digest(),
                            nextTime(history.attemptsOf(file.version())), Outcome.FAILED));
                }
                throw history.digestMismatch(file);
            }
            case LATEST_FAILED -> throw SchemaHistory.recordedFailed(file);
            case INTACT -> true;
        };
    }

    /**
     * Has the service run one file and its SUCCESS row (one transaction); true when applied by this call. A failure rolls the
     * transaction back; the FAILED row is written afterwards, outside it.
     */
    private boolean apply(MigrationFile file) {
        List<Row> before = new SchemaHistory(repository.rows()).attemptsOf(file.version());
        try {
            applier.apply(file, nextTime(before));
            LOG.info("applied V{} {}", file.version(), file.fileName());
            return true;
        } catch (DataAccessException | TransactionException e) {
            return failed(file, before, e);
        }
    }

    private boolean failed(MigrationFile file, List<Row> before, RuntimeException cause) {
        InfrastructureException failure = new InfrastructureException(InfrastructureCode.SCHEMA_MIGRATION_FAILED,
                List.of(String.valueOf(file.version()), file.fileName()), cause);
        try {
            if (appliedByAnotherProcess(file, before)) {
                LOG.info("V{} was applied by another migration process", file.version(), cause);
                return false;
            }
            List<Row> now = new SchemaHistory(repository.rows()).attemptsOf(file.version());
            repository.append(new Row(file.version(), file.fileName(), file.digest(), nextTime(now), Outcome.FAILED));
        } catch (DataAccessException recording) {
            // the history cannot be read or written either: the failure of the file is still what the caller must learn,
            // so it stays the cause, and the failure of recording it is attached, not thrown in its place
            failure.addSuppressed(recording);
        }
        throw failure;
    }

    /**
     * A file that fails may only have met another migration process that is applying the same version (a DBMS
     * that commits every DDL statement at once shows its half-done file as an error). The history decides: this
     * waits up to {@code peerWait} for a SUCCESS row of the same digest that was not there before. The wait is a
     * poll of the history ({@link #PEER_POLL_MILLIS}) because the other process is another program and gives no event.
     */
    private boolean appliedByAnotherProcess(MigrationFile file, List<Row> before) {
        long deadline = System.nanoTime() + properties.peerWait().toNanos();
        while (true) {
            List<Row> now = new SchemaHistory(repository.rows()).attemptsOf(file.version());
            if (now.size() > before.size() && now.stream()
                    .anyMatch(r -> r.outcome() == Outcome.SUCCESS && Objects.equals(r.sha256(), file.digest()))) {
                return true;
            }
            if (System.nanoTime() - deadline >= 0) {
                return false;
            }
            try {
                Thread.sleep(PEER_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InfrastructureException(InfrastructureCode.SCHEMA_MIGRATION_INTERRUPTED, List.of(), e);
            }
        }
    }

    /** Now (microseconds), but strictly after the previous attempt of the same version, which the key needs. */
    private Instant nextTime(List<Row> rowsOfVersion) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (rowsOfVersion.isEmpty()) {
            return now;
        }
        Instant last = rowsOfVersion.getLast().appliedAt();
        return now.isAfter(last) ? now : last.plus(1, ChronoUnit.MICROS);
    }
}
