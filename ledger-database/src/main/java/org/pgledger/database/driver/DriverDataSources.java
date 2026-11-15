package org.pgledger.database.driver;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.pgledger.database.repository.SchemaHistoryRepository;
import org.pgledger.database.repository.SqlBuilder;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import java.sql.DatabaseMetaData;
import java.util.List;

/**
 * Builds the pool from a registered driver: {@code Driver} -> {@link SimpleDriverDataSource} -> Hikari. The
 * {@code DriverManager} is not involved, so the driver of the registered jar is the one that connects.
 *
 * <p>Why the pool is assembled here and not by Spring Boot (principle 4.1, "the {@code DataSource} and the pool come from
 * Boot's auto-configuration"): Boot builds a Hikari pool from {@code spring.datasource.*}, which names a driver class that
 * Boot loads from the application class path. The driver of this product is a jar that the user registers at run time and
 * that lives in an isolated class loader ({@link DriverLoader}), so the {@link java.sql.Driver} instance is handed to
 * {@link SimpleDriverDataSource} (Spring's own {@code DataSource} for a given driver) and that one to Hikari, which Boot
 * would have used too. The pool settings are the ones of {@code ledger.jdbc.*}.
 */
public final class DriverDataSources {

    private DriverDataSources() {
    }

    /**
     * The pool is made ready after the product of its connections is checked against the configured JDBC URL
     * ({@link #requireConfiguredProduct}); a pool that fails the check is closed.
     *
     * @param schema the schema every connection uses, or {@code null} for the default schema of the account. It need not
     *               exist yet (the migrator creates it through this pool): the pool names it with
     *               {@code Connection.setSchema} ({@code HikariConfig.setSchema}), which on PostgreSQL is
     *               {@code SET SESSION search_path} and takes a schema that is not there yet; PostgreSQL's documentation says
     *               "The first schema in the search path that exists is the default location for creating new objects"
     *               (https://www.postgresql.org/docs/current/ddl-schemas.html). A DBMS that refuses a schema that does not
     *               exist needs the schema made before the first run (not verified here: the tests run on PostgreSQL only).
     */
    public static HikariDataSource create(RegisteredDriver registered, LedgerJdbcProperties properties,
            String schema) {
        // a registration written by hand cannot make the embedded database the ledger database either
        EmbeddedDatabaseRefusal.require(properties.url(), registered.driver().getClass().getName());
        registered.requireAccepts(properties.url());
        SimpleDriverDataSource simple = new SimpleDriverDataSource(registered.driver(), properties.url(),
                properties.username(), properties.password());
        HikariConfig config = new HikariConfig();
        config.setPoolName("ledger");
        config.setDataSource(simple);
        config.setMaximumPoolSize(properties.poolSize());
        // The row-lock and conditional-update logic of the services is written for READ_COMMITTED, so it is asked for by the
        // JDBC standard name instead of left to the default of the DBMS, which a server or a driver setting can change.
        // A service that needs another isolation level declares it on its @Transactional method.
        config.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        if (schema != null) {
            config.setSchema(foldedSchema(simple, schema));
        }
        // principle 3.9: the one place that makes the pool; only @Bean methods call this factory (the one of
        // LedgerDataSourceConfiguration, and the one of a command line tool that has a second data source), and the
        // container closes what they return (destroyMethod = "close")
        HikariDataSource pool = new HikariDataSource(config);
        boolean ready = false;
        try {
            requireConfiguredProduct(pool, properties.url());
            ready = true;
            return pool;
        } finally {
            if (!ready) {
                pool.close();
            }
        }
    }

    /**
     * Refuses a pool whose connections are of another database product than the configured JDBC URL names, and a product that
     * the ledger has no SQL for ({@link SqlBuilder}): the SQL that differs by product is chosen from the product of the
     * connection, so a URL that says one product while the driver connects to another would run the wrong dialect.
     *
     * @throws InfrastructureException DATABASE_PRODUCT_MISMATCH, DATABASE_PRODUCT_UNSUPPORTED, or INTERNAL_ERROR when the
     *         product cannot be read
     */
    public static void requireConfiguredProduct(DataSource dataSource, String jdbcUrl) {
        SqlBuilder.requireSame(jdbcUrl,
                SchemaHistoryRepository.inspect(dataSource, DatabaseMetaData::getDatabaseProductName));
    }

    /**
     * The connections of the application account for the check of {@code setup}: a separate data source of that account (no
     * pool, one connection per use) through the registered driver, used while the run itself is connected as another account
     * (the owner account). A second data source is genuinely needed here (principle 3.9): the point of the check is to connect as
     * the application account, which the ledger data source of the run, connected as the owner account, cannot do. It is called
     * from the {@code @Bean} method of the one holder ({@code ApplicationAccountAccess}), holds no pool and no open connection
     * ({@link ApplicationAccountDataSource} connects at each call), so there is nothing for the container to close.
     *
     * @param application the settings of the application account ({@code ledger.jdbc.*} without the owner account of the run)
     * @param schema      the schema every connection uses, or {@code null} for the default schema of the account
     */
    public static DataSource applicationAccountDataSource(RegisteredDriver registered, LedgerJdbcProperties application,
            String schema) {
        EmbeddedDatabaseRefusal.require(application.url(), registered.driver().getClass().getName());
        registered.requireAccepts(application.url());
        return new ApplicationAccountDataSource(registered.driver(), application.url(), application.username(),
                application.password(), schema);
    }

    private static String foldedSchema(SimpleDriverDataSource simple, String schema) {
        try {
            return SchemaHistoryRepository.inspect(simple, metaData -> SchemaHistoryRepository.fold(metaData, schema));
        } catch (DataAccessException | InfrastructureException e) {
            // a refused connection (Spring DataAccessException) or an unreadable metadata (INTERNAL_ERROR)
            throw new InfrastructureException(InfrastructureCode.DRIVER_INVALID,
                    List.of("cannot connect with the registered driver: " + e.getMessage()), e);
        }
    }
}
