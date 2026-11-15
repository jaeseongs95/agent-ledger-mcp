package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.pgledger.database.repository.SqlBuilder;
import org.pgledger.database.repository.SqlBuilder.Database;

/**
 * RUN-023, RUN-024, RUN-025: the queries that prove the encoding of the database are the text of the dialect of {@link SqlBuilder}
 * (defect D-0047, user decision 「SqlBuilder 방언이 정함」). No database here. The expected queries are written out product by product,
 * character for character: a product added to {@link Database} fails the compilation of the exhaustive switches of the builder first,
 * and this test then lacks its row. How the answer of a database is judged is {@code DatabaseEncodingTest}; the probe on the test
 * PostgreSQL is RUN-029 in {@code SchemaMigratorTest}.
 */
class EncodingProbeQueryTest {

    private static final String PROBE_VARCHAR = "SELECT CHAR_LENGTH(CAST(? AS VARCHAR(40))), CAST(? AS VARCHAR(40))";
    private static final String PROBE_CHAR = "SELECT CHAR_LENGTH(CAST(? AS CHAR(40))), CAST(? AS CHAR(40))";

    private static final Map<Database, String> ENCODING_PROBE = Map.of(
            Database.POSTGRESQL, PROBE_VARCHAR,
            Database.H2, PROBE_VARCHAR,
            Database.MARIADB, PROBE_CHAR,
            Database.MYSQL, PROBE_CHAR,
            Database.ORACLE, "SELECT LENGTH(CAST(? AS VARCHAR2(40))), CAST(? AS VARCHAR2(40)) FROM DUAL",
            Database.DB2, "SELECT CHARACTER_LENGTH(CAST(? AS VARCHAR(40)) USING CODEUNITS32), CAST(? AS VARCHAR(40)) FROM SYSIBM.SYSDUMMY1",
            Database.SQLSERVER, "SELECT LEN(CAST(? AS VARCHAR(40))), CAST(? AS VARCHAR(40))");

    private static final String SCHEMA_CHARSET_PROBE = "SELECT DEFAULT_CHARACTER_SET_NAME, @@character_set_server, "
            + "CASE WHEN LOWER(DEFAULT_CHARACTER_SET_NAME) = 'utf8mb4' AND LOWER(@@character_set_server) = 'utf8mb4' "
            + "THEN 1 ELSE 0 END FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = DATABASE()";

    private static final Map<Database, String> PRODUCT_NAMES = Map.of(
            Database.POSTGRESQL, "PostgreSQL",
            Database.H2, "H2",
            Database.ORACLE, "Oracle",
            Database.DB2, "DB2/LINUXX8664",
            Database.SQLSERVER, "Microsoft SQL Server",
            Database.MARIADB, "MariaDB",
            Database.MYSQL, "MySQL");

    private static SqlBuilder reporting(String product) throws SQLException {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn(product);
        return new SqlBuilder(source);
    }

    @Test
    void run023_everyDialectHasItsQueryAndTheTableCoversEveryDialect() {
        assertThat(ENCODING_PROBE.keySet()).as("a row for every dialect").containsExactlyInAnyOrder(Database.values());
        assertThat(PRODUCT_NAMES.keySet()).containsExactlyInAnyOrder(Database.values());
        for (Database database : Database.values()) {
            assertThat(SqlBuilder.generateEncodingProbe(database)).as(database.name()).isEqualTo(ENCODING_PROBE.get(database));
        }
    }

    @ParameterizedTest
    @EnumSource(Database.class)
    void run023_everyQueryHasTwoPlaceholdersAndNoValueInIt(Database database) {
        String query = SqlBuilder.generateEncodingProbe(database);

        assertThat(query.chars().filter(c -> c == '?').count()).as(database.name()).isEqualTo(2);
        assertThat(query).doesNotContain(SqlBuilder.ENCODING_PROBE_TEXT).doesNotContain("'");
    }

    @ParameterizedTest
    @EnumSource(Database.class)
    void run023_theInstanceFormTakesTheProductFromTheNameTheConnectionReports(Database database) throws SQLException {
        assertThat(reporting(PRODUCT_NAMES.get(database)).generateEncodingProbe()).isEqualTo(ENCODING_PROBE.get(database));
    }

    @ParameterizedTest
    @EnumSource(Database.class)
    void run024_theSchemaCharsetProbeIsOnlyForMariaDbAndMySql(Database database) throws SQLException {
        boolean has = database == Database.MARIADB || database == Database.MYSQL;
        Optional<String> expected = has ? Optional.of(SCHEMA_CHARSET_PROBE) : Optional.empty();

        assertThat(SqlBuilder.generateSchemaCharsetProbe(database)).as(database.name()).isEqualTo(expected);
        assertThat(reporting(PRODUCT_NAMES.get(database)).generateSchemaCharsetProbe()).as(database.name() + " by name").isEqualTo(expected);
    }

    @Test
    void run024_theNamesAreComparedByTheDatabaseNotByTheCaller() {
        String query = SqlBuilder.generateSchemaCharsetProbe(Database.MYSQL).orElseThrow();

        assertThat(query).contains("LOWER(DEFAULT_CHARACTER_SET_NAME) = 'utf8mb4'").contains("LOWER(@@character_set_server) = 'utf8mb4'")
                .contains("THEN 1 ELSE 0 END");
        assertThat(SqlBuilder.generateSchemaCharsetProbe(Database.MARIADB)).isEqualTo(SqlBuilder.generateSchemaCharsetProbe(Database.MYSQL));
    }

    @ParameterizedTest
    @EnumSource(Database.class)
    void run025_onlyH2CountsUtf16CodeUnits(Database database) throws SQLException {
        boolean expected = database != Database.H2;

        assertThat(SqlBuilder.countsCodePoints(database)).as(database.name()).isEqualTo(expected);
        assertThat(reporting(PRODUCT_NAMES.get(database)).countsCodePoints()).as(database.name() + " by name").isEqualTo(expected);
    }
}
