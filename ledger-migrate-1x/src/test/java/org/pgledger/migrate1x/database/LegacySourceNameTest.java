package org.pgledger.migrate1x.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.testing.database.TestDatabase;
import org.pgledger.testing.database.TestSchema;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * DBN-002: the schema and table names that the repositories give to {@code DatabaseMetaData.getTables} are LIKE patterns (JDBC),
 * where {@code _} and {@code %} are wildcards. A neighbour schema that differs from the 1.x schema only where its name has an
 * {@code _} must not be taken for it. On the PostgreSQL of the test PC (DBN-001, the same rule for the schema of 2.0, stays in the
 * database module).
 */
class LegacySourceNameTest {

    /** The name of the test schema with every {@code _} replaced by {@code x}: matches the pattern of the name, is not it. */
    private static String neighbour(TestSchema schema) {
        String name = schema.name().replace('_', 'x');
        assertThat(name).isNotEqualTo(schema.name());
        return name;
    }

    @Test
    @DisplayName("DBN-002 the _migration table of the 1.x schema is looked up by its exact schema and table name: a neighbour schema "
            + "with such a table does not make an empty 1.x schema complete enough")
    void migrationTableIsFoundByItsExactName() {
        TestDatabase database = TestDatabase.shared();
        try (TestSchema source = database.newSchema()) {
            String other = neighbour(source);
            database.execute("CREATE SCHEMA " + other);
            try {
                database.execute("CREATE TABLE " + other + "._migration (step VARCHAR(40))");
                LegacySourceRepository repository = new LegacySourceRepository(JdbcClient.create(source.dataSource()),
                        source.dataSource(), source.name());

                assertThat(repository.missingSteps()).isEqualTo(List.of("schema", "defect_case", "common_code", "created_by"));
            } finally {
                database.execute("DROP SCHEMA " + other + " CASCADE");
            }
        }
    }
}
