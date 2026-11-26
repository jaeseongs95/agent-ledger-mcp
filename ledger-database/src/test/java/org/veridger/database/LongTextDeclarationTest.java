// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.veridger.database.repository.SqlBuilder;
import org.veridger.database.repository.SqlBuilder.Database;
import org.veridger.domain.error.InfrastructureCode;
import org.veridger.domain.error.InfrastructureException;

/**
 * RUN-001, RUN-002, RUN-020: the declaration that the placeholder {@code ${long_text}} of the schema files stands for is decided by the
 * dialect of {@link SqlBuilder} (common migration runner, edition 4). No database here. The declaration of the test PostgreSQL that holds
 * 40000 characters is RUN-004, in {@code SchemaMigratorTest}. The expected declarations are written out here, product by product: a
 * product added to {@link Database} fails the compilation of the exhaustive switch of the builder first, and this test then lacks its row.
 */
class LongTextDeclarationTest {

    private static final Map<Database, String> EXPECTED = Map.of(
            Database.POSTGRESQL, "TEXT",
            Database.H2, "CHARACTER LARGE OBJECT",
            Database.ORACLE, "CLOB",
            Database.DB2, "CLOB(100M)",
            Database.SQLSERVER, "NVARCHAR(MAX)",
            Database.MARIADB, "LONGTEXT",
            Database.MYSQL, "LONGTEXT");

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
    void run001_everyDialectHasItsDeclarationAndTheTableCoversEveryDialect() {
        assertThat(EXPECTED.keySet()).as("a row for every dialect").containsExactlyInAnyOrder(Database.values());
        assertThat(PRODUCT_NAMES.keySet()).containsExactlyInAnyOrder(Database.values());
        for (Database database : Database.values()) {
            assertThat(SqlBuilder.longTextType(database)).as(database.name()).isEqualTo(EXPECTED.get(database));
        }
    }

    @ParameterizedTest
    @EnumSource(Database.class)
    void run002_theMigratorTakesTheDialectFromTheProductNameOfTheConnection(Database database) throws SQLException {
        assertThat(reporting(PRODUCT_NAMES.get(database)).longTextType()).isEqualTo(EXPECTED.get(database));
    }

    @Test
    void run002_aProductWithoutADialectIsRefused() throws SQLException {
        InfrastructureException refused = catchThrowableOfType(InfrastructureException.class,
                () -> reporting("Apache Derby").longTextType());
        assertThat(refused).isNotNull();
        assertThat(refused.code()).isEqualTo(InfrastructureCode.DATABASE_PRODUCT_UNSUPPORTED);
    }

    /** RUN-020: Db2 gives a CLOB without a length only 1M, so its declaration carries a length; no declaration is blank or a placeholder. */
    @Test
    void run020_db2WritesALengthAndNoDeclarationIsBlankOrAPlaceholder() {
        assertThat(SqlBuilder.longTextType(Database.DB2)).contains("(");
        Arrays.stream(Database.values()).forEach(database -> assertThat(SqlBuilder.longTextType(database))
                .as(database.name()).isNotBlank().doesNotContain("${"));
    }
}
