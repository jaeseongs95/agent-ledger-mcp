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
 * The use case "apply one schema file": the statements of the file and the SUCCESS row of {@code schema_history} are one
 * transaction (principle 4.4), so a file that fails leaves neither a half-built schema (on a DBMS with transactional DDL) nor a
 * SUCCESS row. The loop over the files, the judgement of the history and the recording of a failure belong to the caller
 * ({@link SchemaMigrator}), which calls this service once per file, so every call goes through the transaction proxy of the
 * container.
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
     * Runs the file and appends its SUCCESS row, in one transaction. The populator gets its connection from Spring's
     * {@code DataSourceUtils}, so it runs on the connection of the transaction, like the history row. The file is applied with
     * {@code longTextType} in place of {@value LongTextType#PLACEHOLDER}: a plain text replacement made before the file runs (the
     * digest is of the file, so it does not depend on the type).
     *
     * @param appliedAt the time of the row (strictly after the previous attempt of the same version, which the key needs)
     * @throws org.springframework.dao.DataAccessException when a statement or the row fails; the transaction is rolled back
     */
    @Transactional
    public void apply(MigrationFile file, Instant appliedAt, String longTextType) {
        String sql = file.text().replace(LongTextType.PLACEHOLDER, longTextType);
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(false, false, "UTF-8",
                new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
        DatabasePopulatorUtils.execute(populator, dataSource);
        history.append(new Row(file.version(), file.fileName(), file.digest(), appliedAt, Outcome.SUCCESS));
    }
}
