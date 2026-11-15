package org.pgledger.database.repository;

import java.sql.Connection;
import java.util.List;
import javax.sql.DataSource;
import org.pgledger.database.driver.DatabaseProductName;
import org.pgledger.database.driver.RegisteredDriver;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.springframework.boot.jdbc.DatabaseDriver;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;

/**
 * Builds the SQL that differs by database product, taking the product from the actual database the first time it is
 * needed. The ledger runs on the DBMS the user installs, so a row limit is the clause of that product, never one product's
 * text for all and never a driver call that each driver may handle in its own way. Every value stays a bind parameter;
 * the builder only joins fixed fragments.
 *
 * <p>Ported from the AGS SqlBuilder (AGS repository jaeseongs95/sennard-source, commit 1ad1b8e7; AGS is AGPL-3.0-or-later,
 * like the ledger 2.0). Changes for the ledger: its own business failures ({@link InfrastructureException}) instead of the
 * AGS store exceptions; the product name is read through {@link DatabaseProductName}, the one class that catches the JDBC
 * exception (the database module catches {@code SQLException} only in the driver package); the instance form of
 * {@code requireSame}, which the ledger has no caller for, is not carried over.
 *
 * <p>No transaction is opened here (principle 4.4): the connection is taken with {@link DataSourceUtils}, so a caller inside
 * a transaction reuses the connection of that transaction, and is released the same way.
 */
@Component
public class SqlBuilder {

    /** The database products the ledger builds SQL for. */
    public enum Database {
        H2, POSTGRESQL, ORACLE, DB2, SQLSERVER, MARIADB, MYSQL
    }

    private final DataSource dataSource;
    private volatile Database resolved;

    /** @param dataSource the one DataSource of the application (principle 3.9) */
    public SqlBuilder(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private Database database() {
        Database result = resolved;
        if (result != null) {
            return result;
        }
        // Take the connection before the lock: another thread may hold the only connection of the pool in a transaction,
        // and a thread that waited for a connection inside the lock would block it for good.
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            synchronized (this) {
                result = resolved;
                if (result == null) {
                    result = ofProductName(DatabaseProductName.of(connection));
                    resolved = result;
                }
                return result;
            }
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private static Database database(DatabaseDriver driver, String named) {
        return switch (driver) {
            case H2 -> Database.H2;
            case POSTGRESQL -> Database.POSTGRESQL;
            case ORACLE -> Database.ORACLE;
            case DB2 -> Database.DB2;
            case SQLSERVER -> Database.SQLSERVER;
            case MARIADB -> Database.MARIADB;
            case MYSQL -> Database.MYSQL;
            default -> throw new InfrastructureException(InfrastructureCode.DATABASE_PRODUCT_UNSUPPORTED,
                    List.of(String.valueOf(named)));
        };
    }

    /** The row-limit clause of the actual database; the parameter is a JDBC placeholder, not the value. */
    public String generateLimit(String parameter) {
        return generateLimit(database(), parameter);
    }

    /** The row-limit clause of {@code database}; the parameter is a JDBC placeholder, not the value. */
    public static String generateLimit(Database database, String parameter) {
        // PostgreSQL: https://www.postgresql.org/docs/current/sql-select.html
        // H2: https://h2database.com/html/commands.html#select
        // Oracle: https://docs.oracle.com/en/database/oracle/oracle-database/23/sqlrf/SELECT.html
        // Db2: https://www.ibm.com/docs/en/db2?topic=design-guidelines-restricting-select-statements
        // SQL Server: https://learn.microsoft.com/en-us/sql/t-sql/queries/select-order-by-clause-transact-sql
        // MariaDB 10.6+: https://mariadb.com/docs/server/reference/sql-statements/data-manipulation/selecting-data/select-offset-fetch
        // MySQL: https://dev.mysql.com/doc/refman/8.4/en/select.html
        return switch (database) {
            case H2, POSTGRESQL, ORACLE, DB2 -> "FETCH FIRST " + parameter + " ROWS ONLY";
            case SQLSERVER, MARIADB -> "OFFSET 0 ROWS FETCH NEXT " + parameter + " ROWS ONLY";
            case MYSQL -> "LIMIT " + parameter;
        };
    }

    /** Appends the dialect's row limit to an otherwise complete query (it must end with an ORDER BY). */
    public String applyLimit(String statement, String parameter) {
        return statement + " " + generateLimit(parameter);
    }

    /** Starts a short-lived statement from fixed SQL. */
    public Statement query(String sql) {
        return new Statement(sql);
    }

    /** A statement under construction: fixed fragments only, every value is a placeholder the caller binds. */
    public final class Statement {
        private final StringBuilder sql;

        private Statement(String base) {
            sql = new StringBuilder(base);
        }

        /**
         * A parenthesised group of alternatives joined with OR; an empty group is {@code (1 = 0)}, which matches nothing.
         * The fixed pattern may use {@code %1$d} for the zero-based parameter suffix.
         */
        public Statement orGroup(String pattern, int count) {
            sql.append('(');
            if (count == 0) {
                sql.append("1 = 0");
            }
            for (int index = 0; index < count; index++) {
                if (index > 0) {
                    sql.append(" OR ");
                }
                sql.append(pattern.formatted(index));
            }
            sql.append(')');
            return this;
        }

        public Statement and(String condition) {
            sql.append(" AND ").append(condition);
            return this;
        }

        public Statement orderBy(String columns) {
            sql.append(" ORDER BY ").append(columns);
            return this;
        }

        public Statement append(String fixedSql) {
            sql.append(fixedSql);
            return this;
        }

        /** The statement without a row limit; the product is resolved here, so an unsupported one fails at once. */
        public String build() {
            database();
            return sql.toString();
        }

        /** The statement with the row limit of the actual database. */
        public String limit(String parameter) {
            return applyLimit(build(), parameter);
        }
    }

    /**
     * The product a JDBC URL names (no connection is made).
     *
     * @throws InfrastructureException DATABASE_PRODUCT_UNSUPPORTED for a product the ledger has no SQL for
     */
    public static Database ofJdbcUrl(String jdbcUrl) {
        return database(DatabaseDriver.fromJdbcUrl(jdbcUrl), RegisteredDriver.sanitize(jdbcUrl));
    }

    /**
     * The product a connection reports ({@code DatabaseMetaData.getDatabaseProductName()}).
     *
     * @throws InfrastructureException DATABASE_PRODUCT_UNSUPPORTED for a product the ledger has no SQL for
     */
    public static Database ofProductName(String productName) {
        return database(DatabaseDriver.fromProductName(productName), productName);
    }

    /**
     * Checks the configured URL against the product the connection reports, before the pool is made ready.
     *
     * @throws InfrastructureException DATABASE_PRODUCT_UNSUPPORTED for a product the ledger has no SQL for,
     *         DATABASE_PRODUCT_MISMATCH when the two products differ
     */
    public static void requireSame(String jdbcUrl, String productName) {
        Database configured = ofJdbcUrl(jdbcUrl);
        Database actual = ofProductName(productName);
        if (configured != actual) {
            throw new InfrastructureException(InfrastructureCode.DATABASE_PRODUCT_MISMATCH,
                    List.of(configured.name(), actual.name()));
        }
    }
}
