package org.pgledger.database.schema;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.sql.DataSource;
import org.pgledger.database.repository.SchemaHistoryRepository;
import org.pgledger.database.repository.SchemaHistoryRepository.Outcome;
import org.pgledger.database.repository.SchemaHistoryRepository.Row;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The use case "apply one schema file": one attempt is one transaction (principle 4.4). Its first statement inserts the row of
 * its own {@code (version, attempt)} as FAILED, the claim of the attempt; then come the statements of the file; its last statement
 * turns that one row to SUCCEEDED. So a file that fails leaves neither a half-built schema (on a DBMS with transactional DDL) nor a
 * SUCCEEDED row, and a second runner that claims the same attempt waits in the database for the first one and then gets the
 * duplicate key, which is how it learns that the attempt is taken. The loop over the files, the judgement of the history and the
 * recording of a failure belong to the caller ({@link SchemaMigrator}), which calls this service once per file; the container
 * makes the service a proxy of its class, so a call from outside goes through the transaction and a call from inside this class
 * would not (there is none).
 */
@Service
public class SchemaFileApplier {

    private final DataSource dataSource;
    private final SchemaHistoryRepository history;

    public SchemaFileApplier(DataSource dataSource, SchemaHistoryRepository history) {
        this.dataSource = dataSource;
        this.history = history;
    }

    /**
     * Claims the attempt, runs the file and turns the claim to SUCCEEDED, in one transaction. The populator gets its connection
     * from Spring's {@code DataSourceUtils}, so it runs on the connection of the transaction, like the history statements. The file
     * is applied with {@code longTextType} in place of {@value LongTextType#PLACEHOLDER}: a plain text replacement made before the
     * file runs (the digest is of the file, so it does not depend on the type).
     *
     * @param attempt   the number of this attempt of the version (1 for the first, then one more than the highest recorded)
     * @param appliedAt the time of the row
     * @throws org.springframework.dao.DataAccessException when a statement fails, or when the key of the attempt is taken by
     *         another runner (a {@code DuplicateKeyException}); the transaction is rolled back
     */
    @Transactional
    public void apply(MigrationFile file, int attempt, Instant appliedAt, String longTextType) {
        history.append(new Row(file.version(), attempt, file.fileName(), file.digest(), appliedAt, Outcome.FAILED));
        String sql = file.text().replace(LongTextType.PLACEHOLDER, longTextType);
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(false, false, "UTF-8",
                new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
        DatabasePopulatorUtils.execute(populator, dataSource);
        history.succeed(file.version(), attempt);
    }
}
