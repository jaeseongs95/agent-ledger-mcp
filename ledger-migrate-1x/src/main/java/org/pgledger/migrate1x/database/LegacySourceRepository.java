package org.pgledger.migrate1x.database;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.pgledger.database.repository.SchemaHistoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The read side of the legacy import: the old schema {@code ledger}, on a {@link DataSource} of its own (the connection of
 * the legacy account; in production the same registered driver and DBMS as the new schema, in a test a second embedded
 * database). It only reads, and it joins the transaction of its caller like every repository (principle 4.4): the whole
 * import reads one snapshot, a read-only transaction with the isolation REPEATABLE READ that the service
 * {@code LegacySnapshotService} of the application declares with {@code @Transactional} on the transaction manager of this
 * data source ({@link #TRANSACTION_MANAGER}); Spring hands the read-only hint to {@code Connection.setReadOnly(true)}, so a DBMS
 * that honours it refuses a write, and the rows the validation pass saw are the rows the copy pass and the comparison see. The
 * statements are plain ANSI: a select list, a table of the legacy schema and an {@code ORDER BY}; values that are JSON in the
 * legacy schema are read as text, never converted by a function.
 *
 * <p>A component that only {@link LegacySourceWiring} makes (this package is not scanned), from the settings {@code ledger.import.source.*}. The legacy ledger is genuinely a second database (principle 3.9), so
 * it has a {@link JdbcClient} and a transaction manager of its own, both beans next to its data source and qualified by name:
 * the {@code JdbcClient} and the transaction manager of the ledger database stay the {@code @Primary} ones for every other
 * injection point.
 */
@Component
public final class LegacySourceRepository {

    /** The name of the {@link JdbcClient} bean of the legacy data source. */
    public static final String JDBC_CLIENT = "legacySourceJdbcClient";

    /**
     * The name of the transaction manager bean of the legacy data source: the {@code transactionManager} of
     * {@code @Transactional} on the service method that reads the legacy ledger.
     */
    public static final String TRANSACTION_MANAGER = "legacySourceTransactionManager";

    /** The legacy schema name is an identifier, so it cannot be a bind value: it is checked against this form. */
    private static final Pattern SCHEMA = Pattern.compile("[a-z][a-z0-9_]{0,29}");

    /**
     * The steps of the legacy installer that the import needs: the schema itself and the three later migrations
     * ({@code defect_case}, {@code common_code}, {@code created_by}); a database older than the checkout {@code 9fb62fe}
     * lacks some of them.
     */
    private static final List<String> REQUIRED_STEPS = List.of("schema", "defect_case", "common_code", "created_by");

    private static final String MIGRATION_TABLE = "_migration";

    private final DataSource dataSource;
    private final JdbcClient jdbc;
    private final String schema;

    /**
     * The repository of the legacy database of the settings {@code ledger.import.source.*}, on the legacy beans (a pool that the
     * container owns and closes, and the {@code JdbcClient} over it).
     */
    @Autowired
    public LegacySourceRepository(@Qualifier(JDBC_CLIENT) JdbcClient jdbc,
            @Qualifier(LegacySourceConfiguration.DATA_SOURCE) DataSource dataSource, LegacySourceProperties properties) {
        this(jdbc, dataSource, properties.schema());
    }

    /**
     * @param jdbc       the client over {@code dataSource}
     * @param dataSource the connection of the legacy account
     * @param schema     the legacy schema (lower case letters, digits and {@code _}, at most 30)
     * @throws IllegalArgumentException when the schema is not an identifier of that form
     */
    public LegacySourceRepository(JdbcClient jdbc, DataSource dataSource, String schema) {
        if (schema == null || !SCHEMA.matcher(schema).matches()) {
            throw new IllegalArgumentException("the legacy schema must be lower case letters, digits and _ (max 30): "
                    + schema);
        }
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.schema = schema;
    }

    /** The legacy schema the statements read. */
    public String schema() {
        return schema;
    }

    /**
     * The steps of the legacy installer that are missing (all of them when the schema has no {@code _migration} table), in
     * the order the installer applies them; empty when the database is complete enough to import.
     */
    public List<String> missingSteps() {
        if (!SchemaHistoryRepository.inspect(dataSource, metaData -> {
            try (ResultSet tables = metaData.getTables(null,
                    SchemaHistoryRepository.exactPattern(metaData, SchemaHistoryRepository.fold(metaData, schema)),
                    SchemaHistoryRepository.exactPattern(metaData, SchemaHistoryRepository.fold(metaData, MIGRATION_TABLE)),
                    new String[] {"TABLE"})) {
                return tables.next();
            }
        })) {
            return REQUIRED_STEPS;
        }
        Set<String> present = new HashSet<>(jdbc.sql("SELECT step FROM " + schema + "." + MIGRATION_TABLE)
                .query(String.class).list());
        List<String> missing = new ArrayList<>();
        for (String step : REQUIRED_STEPS) {
            if (!present.contains(step)) {
                missing.add(step);
            }
        }
        return missing;
    }

    /** Calls the consumer with every row of the legacy table, in key order, one row at a time. */
    public void scan(LegacyTable table, Consumer<LegacyRow> consumer) {
        List<LegacyColumn> columns = table.sourceColumns();
        jdbc.sql(table.sourceSelect(schema)).query((RowCallbackHandler) rs -> consumer.accept(
                LegacyRow.ofSource(rs, columns)));
    }
}
