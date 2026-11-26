// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.database.support.DatabaseTest;
import org.veridger.database.support.Databases;
import org.veridger.testing.database.TestDatabase;
import org.veridger.testing.database.TestSchema;
import org.veridger.testing.database.TestSchemaServices;

/**
 * DBN-001: the schema and table names that the repositories give to {@code DatabaseMetaData.getTables} are LIKE
 * patterns (JDBC), where {@code _} and {@code %} are wildcards. A neighbour schema that differs from the ledger schema
 * only where the ledger name has an {@code _} must not be taken for it. On the PostgreSQL of the test PC.
 */
@DatabaseTest
class SchemaNamePatternTest {

    /** The name of the test schema with every {@code _} replaced by {@code x}: matches the pattern of the name, is not it. */
    private static String neighbour(TestSchema schema) {
        String name = schema.name().replace('_', 'x');
        assertThat(name).isNotEqualTo(schema.name());
        return name;
    }

    @Test
    @DisplayName("DBN-001 schema_history of a schema whose name differs only at an underscore is not the history of the ledger schema; "
            + "a table whose name differs only at the underscore of schema_history is not it either")
    void historyTableIsFoundByItsExactName(Databases databases) {
        TestDatabase database = databases.database();
        TestSchema schema = databases.newSchema();
        String other = neighbour(schema);
        database.execute("CREATE SCHEMA " + other);
        try {
            database.execute("CREATE TABLE " + other + ".schema_history (version INTEGER)");
            database.execute("CREATE TABLE " + schema.name() + ".schemaxhistory (version INTEGER)");

            assertThat(TestSchemaServices.history(schema.dataSource()).tableExists()).isFalse();

            database.execute("CREATE TABLE " + schema.name() + ".schema_history (version INTEGER)");
            assertThat(TestSchemaServices.history(schema.dataSource()).tableExists()).isTrue();
        } finally {
            database.execute("DROP SCHEMA " + other + " CASCADE");
        }
    }
}
