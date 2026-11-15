package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.database.repository.SqlBuilder;
import org.pgledger.database.repository.SqlBuilder.Database;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;

/**
 * SQB-002 .. SQB-006: the dialect of {@link SqlBuilder} without a real database. The pure parts (the clause of each product,
 * the check of a URL against a product, the products a URL or a name stands for) call the static methods; the parts that
 * resolve the product from a connection use a DataSource whose connection reports a given product name, which is exactly what
 * is varied here (the connection to the real PostgreSQL is SqlBuilderConnectionTest).
 */
class SqlBuilderTest {

    /** A DataSource that hands out a connection which reports {@code product}, and the metadata that counts the asks. */
    private record Reporting(DataSource source, DatabaseMetaData metaData) {

        static Reporting of(String product) throws SQLException {
            DataSource source = mock(DataSource.class);
            Connection connection = mock(Connection.class);
            DatabaseMetaData metaData = mock(DatabaseMetaData.class);
            when(source.getConnection()).thenReturn(connection);
            when(connection.getMetaData()).thenReturn(metaData);
            when(metaData.getDatabaseProductName()).thenReturn(product);
            return new Reporting(source, metaData);
        }
    }

    /** A product, the name its driver reports and the row limit it gets. */
    private record Dialect(Database database, String productName, String limit) {
    }

    static Stream<Dialect> dialects() {
        return Stream.of(new Dialect(Database.H2, "H2", "FETCH FIRST :count ROWS ONLY"),
                new Dialect(Database.POSTGRESQL, "PostgreSQL", "FETCH FIRST :count ROWS ONLY"),
                new Dialect(Database.ORACLE, "Oracle", "FETCH FIRST :count ROWS ONLY"),
                new Dialect(Database.DB2, "DB2/LINUXX8664", "FETCH FIRST :count ROWS ONLY"),
                new Dialect(Database.SQLSERVER, "Microsoft SQL Server", "OFFSET 0 ROWS FETCH NEXT :count ROWS ONLY"),
                new Dialect(Database.MARIADB, "MariaDB", "OFFSET 0 ROWS FETCH NEXT :count ROWS ONLY"),
                new Dialect(Database.MYSQL, "MySQL", "LIMIT :count"));
    }

    @ParameterizedTest
    @MethodSource("dialects")
    @DisplayName("SQB-002 the row limit of each of the seven products, with a placeholder and never a value")
    void rendersTheRowLimitOfEachProduct(Dialect dialect) {
        assertThat(SqlBuilder.generateLimit(dialect.database(), ":count")).isEqualTo(dialect.limit());
        assertThat(SqlBuilder.generateLimit(dialect.database(), "?")).isEqualTo(dialect.limit().replace(":count", "?"));
    }

    @ParameterizedTest
    @MethodSource("dialects")
    @DisplayName("SQB-002 the builder takes the product from the name the connection reports, and every product has a clause")
    void theBuilderResolvesTheProductFromTheConnection(Dialect dialect) throws SQLException {
        SqlBuilder builder = new SqlBuilder(Reporting.of(dialect.productName()).source());
        assertThat(builder.generateLimit(":count")).isEqualTo(dialect.limit());
        assertThat(builder.applyLimit("SELECT id FROM item ORDER BY id", ":count"))
                .isEqualTo("SELECT id FROM item ORDER BY id " + dialect.limit());
        assertThat(SqlBuilder.ofProductName(dialect.productName())).isEqualTo(dialect.database());
    }

    @Test
    @DisplayName("SQB-002 the product is read from the connection once; later calls do not ask the database again")
    void theProductIsResolvedOnce() throws SQLException {
        Reporting database = Reporting.of("PostgreSQL");
        SqlBuilder builder = new SqlBuilder(database.source());
        for (int i = 0; i < 3; i++) {
            assertThat(builder.generateLimit("?")).isEqualTo("FETCH FIRST ? ROWS ONLY");
        }
        verify(database.metaData(), times(1)).getDatabaseProductName();
    }

    @Test
    @DisplayName("SQB-002 a product with no SQL is refused each time, no refusal is remembered as a product")
    void anUnsupportedProductIsRefusedAndNotCached() throws SQLException {
        Reporting database = Reporting.of("Apache Derby");
        SqlBuilder builder = new SqlBuilder(database.source());
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> builder.generateLimit("?")).isInstanceOfSatisfying(InfrastructureException.class,
                    refused -> {
                        assertThat(refused.code()).isEqualTo(InfrastructureCode.DATABASE_PRODUCT_UNSUPPORTED);
                        assertThat(refused.args()).containsExactly("Apache Derby");
                    });
        }
        verify(database.metaData(), times(2)).getDatabaseProductName();
    }

    @Test
    @DisplayName("SQB-003 requireSame accepts the same product and refuses another one with both products named")
    void requireSameComparesTheConfiguredAndTheConnectedProduct() {
        assertThatCode(() -> SqlBuilder.requireSame("jdbc:postgresql://localhost/db", "PostgreSQL"))
                .doesNotThrowAnyException();
        assertThatCode(() -> SqlBuilder.requireSame("jdbc:mysql://localhost/db", "MySQL")).doesNotThrowAnyException();
        // a URL of one product whose connection reports another one (the product-name path: no real MySQL is needed)
        assertThatThrownBy(() -> SqlBuilder.requireSame("jdbc:mysql://localhost/db", "PostgreSQL"))
                .isInstanceOfSatisfying(InfrastructureException.class, mismatch -> {
                    assertThat(mismatch.code()).isEqualTo(InfrastructureCode.DATABASE_PRODUCT_MISMATCH);
                    assertThat(mismatch.args()).containsExactly("MYSQL", "POSTGRESQL");
                });
        // MariaDB and MySQL are two products: their row limits differ
        assertThatThrownBy(() -> SqlBuilder.requireSame("jdbc:mariadb://localhost/db", "MySQL"))
                .isInstanceOfSatisfying(InfrastructureException.class,
                        mismatch -> assertThat(mismatch.code()).isEqualTo(InfrastructureCode.DATABASE_PRODUCT_MISMATCH));
    }

    @Test
    @DisplayName("SQB-004 a URL names its product without a connection")
    void aJdbcUrlNamesItsProduct() {
        assertThat(SqlBuilder.ofJdbcUrl("jdbc:postgresql://localhost/db")).isEqualTo(Database.POSTGRESQL);
        assertThat(SqlBuilder.ofJdbcUrl("jdbc:oracle:thin:@localhost:1521/db")).isEqualTo(Database.ORACLE);
        assertThat(SqlBuilder.ofJdbcUrl("jdbc:db2://localhost/db")).isEqualTo(Database.DB2);
        assertThat(SqlBuilder.ofJdbcUrl("jdbc:sqlserver://localhost")).isEqualTo(Database.SQLSERVER);
        assertThat(SqlBuilder.ofJdbcUrl("jdbc:mariadb://localhost/db")).isEqualTo(Database.MARIADB);
        assertThat(SqlBuilder.ofJdbcUrl("jdbc:mysql://localhost/db")).isEqualTo(Database.MYSQL);
        assertThat(SqlBuilder.ofJdbcUrl("jdbc:h2:mem:x")).isEqualTo(Database.H2);
    }

    @Test
    @DisplayName("SQB-004 a URL or a product name that no dialect is written for is refused, and a URL password is not shown")
    void anUnsupportedProductIsRefused() {
        assertThatThrownBy(() -> SqlBuilder.ofJdbcUrl("jdbc:derby:memory:x;create=true"))
                .isInstanceOfSatisfying(InfrastructureException.class,
                        refused -> assertThat(refused.code()).isEqualTo(InfrastructureCode.DATABASE_PRODUCT_UNSUPPORTED));
        assertThatThrownBy(() -> SqlBuilder.ofJdbcUrl("jdbc:nothing://localhost/db?user=a&password=top-secret"))
                .isInstanceOfSatisfying(InfrastructureException.class, refused -> {
                    assertThat(refused.code()).isEqualTo(InfrastructureCode.DATABASE_PRODUCT_UNSUPPORTED);
                    assertThat(refused.args().getFirst()).doesNotContain("top-secret").contains("***");
                });
        assertThatThrownBy(() -> SqlBuilder.ofProductName("Some Other Database"))
                .isInstanceOfSatisfying(InfrastructureException.class, refused -> {
                    assertThat(refused.code()).isEqualTo(InfrastructureCode.DATABASE_PRODUCT_UNSUPPORTED);
                    assertThat(refused.args()).containsExactly("Some Other Database");
                });
        // a name that is absent is refused with a business failure, not a NullPointerException
        assertThatThrownBy(() -> SqlBuilder.ofProductName(null)).isInstanceOfSatisfying(InfrastructureException.class,
                refused -> assertThat(refused.code()).isEqualTo(InfrastructureCode.DATABASE_PRODUCT_UNSUPPORTED));
    }

    @ParameterizedTest
    @EnumSource(Database.class)
    @DisplayName("SQB-004 every product the builder knows has a row-limit clause (a new product cannot be added without one)")
    void everyProductHasAClause(Database database) {
        assertThat(SqlBuilder.generateLimit(database, "?")).contains("?");
    }

    @Test
    @DisplayName("SQB-005 an alternative group of n parts, of one part, and of none (which matches nothing)")
    void anOrGroupHoldsTheAlternativesOrMatchesNothing() throws SQLException {
        SqlBuilder builder = new SqlBuilder(Reporting.of("PostgreSQL").source());
        String pattern = "(host = :host%1$d AND session_id = :session%1$d)";
        assertThat(builder.query("SELECT * FROM prompt WHERE ").orGroup(pattern, 1).orderBy("received_at DESC")
                .limit(":row_count")).isEqualTo("SELECT * FROM prompt WHERE ((host = :host0 AND session_id = :session0)) "
                        + "ORDER BY received_at DESC FETCH FIRST :row_count ROWS ONLY");
        assertThat(builder.query("SELECT * FROM prompt WHERE ").orGroup(pattern, 3).and("text = :text")
                .orderBy("received_at DESC").limit(":row_count")).isEqualTo(
                        "SELECT * FROM prompt WHERE ((host = :host0 AND session_id = :session0) OR "
                                + "(host = :host1 AND session_id = :session1) OR "
                                + "(host = :host2 AND session_id = :session2)) AND text = :text "
                                + "ORDER BY received_at DESC FETCH FIRST :row_count ROWS ONLY");
        assertThat(builder.query("SELECT ").orGroup("event = ?", 0).build()).isEqualTo("SELECT (1 = 0)");
        assertThat(builder.query("SELECT ").orGroup("event = ?", 2).build()).isEqualTo("SELECT (event = ? OR event = ?)");
        assertThat(builder.query("SELECT id FROM item").append(" WHERE id > ?").build())
                .isEqualTo("SELECT id FROM item WHERE id > ?");
    }

    @Test
    @DisplayName("SQB-006 the statements the builder gives for a product it has no clause for are never made (build fails first)")
    void buildingAStatementResolvesTheProduct() throws SQLException {
        SqlBuilder builder = new SqlBuilder(Reporting.of("Apache Derby").source());
        assertThatThrownBy(() -> builder.query("SELECT 1 FROM t").build()).isInstanceOf(InfrastructureException.class);
        assertThatThrownBy(() -> builder.query("SELECT 1 FROM t").limit("?")).isInstanceOf(InfrastructureException.class);
    }
}
