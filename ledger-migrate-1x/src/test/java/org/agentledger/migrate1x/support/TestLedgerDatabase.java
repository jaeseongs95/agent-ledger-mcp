// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.migrate1x.support;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.agentledger.database.schema.SchemaProperties;
import org.agentledger.testing.database.TestDatabase;
import org.agentledger.testing.database.TestSchema;
import org.agentledger.testing.database.TestSchemaServices;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The PostgreSQL of the test PC as the ledger database of a tool test (TEST_CASES.md 1.4a): a schema of its own in the test
 * database ({@link TestSchema}), with the schema files migrated by the real {@code SchemaMigrator}. Its DataSource is the shared
 * pool of the test JVM, every connection using that schema ({@link TestDatabase#scoped}), so the connections of the JVM stay few.
 * The test reads the rows the tool wrote through {@link #rows}; column names are the lower-case names of PostgreSQL.
 * {@link #close()} drops the schema.
 */
public final class TestLedgerDatabase implements AutoCloseable {

    private final TestSchema schema;
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    private TestLedgerDatabase(TestSchema schema) {
        this.schema = schema;
        this.dataSource = schema.dataSource();
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** A new schema with the whole ledger schema migrated. */
    public static TestLedgerDatabase migrated() {
        TestLedgerDatabase database = empty();
        // No catch: a schema that could not be migrated is dropped again, and the failure goes on to the test.
        boolean ready = false;
        try {
            TestSchemaServices.migrator(database.dataSource,
                    new SchemaProperties(SchemaProperties.DEFAULT_LOCATION, true, database.schemaName()),
                    Clock.systemUTC()).migrate();
            ready = true;
        } finally {
            if (!ready) {
                database.close();
            }
        }
        return database;
    }

    /** A new, empty schema (no table; the schema files are not migrated). */
    public static TestLedgerDatabase empty() {
        return new TestLedgerDatabase(TestDatabase.shared().newSchema());
    }

    /** The shared pool of the owner account, every connection using the schema of this database. */
    public DataSource dataSource() {
        return dataSource;
    }

    /** The name of the schema of this database in the test database. */
    public String schemaName() {
        return schema.name();
    }

    /** The rows of an ANSI query (the column names are the lower-case names of PostgreSQL). */
    public List<Map<String, Object>> rows(String sql, Object... arguments) {
        return jdbc.queryForList(sql, arguments);
    }

    /** Runs an ANSI DML statement (the test changes a row the product would never change) and returns the row count. */
    public int update(String sql, Object... arguments) {
        return jdbc.update(sql, arguments);
    }

    /** Drops the schema with everything in it; the shared pool stays open. */
    @Override
    public void close() {
        schema.close();
    }
}
