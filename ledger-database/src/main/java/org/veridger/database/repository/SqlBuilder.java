// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.repository;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.veridger.database.driver.DatabaseProductName;
import org.veridger.database.driver.RegisteredDriver;
import org.veridger.domain.error.InfrastructureCode;
import org.veridger.domain.error.InfrastructureException;
import org.springframework.boot.jdbc.DatabaseDriver;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;

/**
 * Builds the SQL that differs by database product, taking the product from the actual database the first time it is
 * needed. The ledger runs on the DBMS the user installs, so the places that differ by product, the row limit, the
 * declaration of the long text column type and the probe that proves the encoding of the database (see
 * {@link #generateEncodingProbe(Database)}), are the text of that product, never one product's text for all and never a
 * driver call that each driver may handle in its own way. Every value stays a bind parameter; the builder only joins fixed
 * fragments.
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

    /**
     * Dialect-compatible typed {@code NULL} character projection for {@code UNION ALL} queries (matching
     * {@link #generateEncodingProbe(Database)}: MySQL/MariaDB {@code CAST()} accepts {@code CHAR(N)} rather than
     * {@code VARCHAR(N)}, Oracle uses {@code VARCHAR2(N)}, others use {@code VARCHAR(N)}).
     */
    public static String generateNullText(Database database, int length) {
        if (length < 1) {
            throw new IllegalArgumentException("length must be positive: " + length);
        }
        return switch (database) {
            case POSTGRESQL, H2, DB2, SQLSERVER -> "CAST(NULL AS VARCHAR(" + length + "))";
            case MARIADB, MYSQL -> "CAST(NULL AS CHAR(" + length + "))";
            case ORACLE -> "CAST(NULL AS VARCHAR2(" + length + "))";
        };
    }

    String nullText(int length) {
        return generateNullText(database(), length);
    }

    /**
     * Whether JDBC {@code PreparedStatement.executeBatch()} on this database reliably reports exact per-row update
     * counts ({@code >= 0}) instead of {@code Statement.SUCCESS_NO_INFO}.
     */
    boolean supportsExactBatchUpdateCounts() {
        return switch (database()) {
            case POSTGRESQL, H2, SQLSERVER -> true;
            case ORACLE, DB2, MARIADB, MYSQL -> false;
        };
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

    /** What the schema files write where a long text column type belongs; the migrator puts {@link #longTextType()} in its place. */
    public static final String LONG_TEXT_PLACEHOLDER = "${long_text}";

    /** The long text column type of the actual database, for {@link #LONG_TEXT_PLACEHOLDER}. */
    public String longTextType() {
        return longTextType(database());
    }

    /**
     * The long text column type of {@code database}: the type of a column that holds far more than the longest text of the ledger
     * (a field is at most 20000 characters, see {@code TextRule}, and the history keeps a JSON of the record). It is decided by the
     * dialect, as the row limit is, and not read from the driver: the MariaDB driver reports {@code [CHARACTER SET charset_name]
     * [COLLATE collation_name]} as the {@code CREATE_PARAMS} of LONGTEXT, so what the driver says cannot be judged. A DBMS whose default
     * limit is small when the length is omitted gets a length.
     */
    public static String longTextType(Database database) {
        // PostgreSQL: TEXT stores strings of any length (about 1 GB at most): https://www.postgresql.org/docs/current/datatype-character.html
        // H2: CHARACTER LARGE OBJECT, length optional, for very large Unicode strings: https://h2database.com/html/datatypes.html#character_large_object_type
        // Oracle: CLOB, (4 GB - 1) * block size, database character set: https://docs.oracle.com/en/database/oracle/oracle-database/23/sqlrf/Data-Types.html
        // Db2: CLOB is 1M code units when the length is omitted, so a length is written (100M, a CLOB goes to 2 GB - 1):
        //      https://www.ibm.com/docs/en/db2/11.5?topic=statements-create-table
        //      https://www.ibm.com/docs/en/db2/11.5?topic=list-character-strings
        // SQL Server: NVARCHAR(MAX), 2^31-1 characters (2 GB), Unicode (UTF-16); VARCHAR(MAX) would store Korean only if the collation's code page has it:
        //      https://learn.microsoft.com/en-us/sql/t-sql/data-types/nchar-and-nvarchar-transact-sql
        // MariaDB: LONGTEXT, 4 GB, the character set of the table (the ledger requires UTF-8): https://mariadb.com/kb/en/longtext/
        // MySQL: LONGTEXT, L + 4 bytes, L < 2^32: https://dev.mysql.com/doc/refman/8.4/en/storage-requirements.html
        return switch (database) {
            case POSTGRESQL -> "TEXT";
            case H2 -> "CHARACTER LARGE OBJECT";
            case ORACLE -> "CLOB";
            case DB2 -> "CLOB(100M)";
            case SQLSERVER -> "NVARCHAR(MAX)";
            case MARIADB, MYSQL -> "LONGTEXT";
        };
    }

    /**
     * The text that the encoding probe sends: two Korean letters (U+D55C, U+AE00) and one emoji outside the Basic Multilingual Plane
     * (U+1F600), written as {@code \}{@code u} escapes, so that the encoding of this source file decides nothing (the build compiles in
     * UTF-8 all the same, and {@code DatabaseEncodingTest} asserts that the constant equals the UTF-8 literal of the three characters,
     * three code points, ten bytes in UTF-8, four UTF-16 code units). A database that does not store UTF-8 cannot give it back (it
     * answers a replacement character or a question mark, or a length counted in bytes, or fails with a data exception).
     */
    public static final String ENCODING_PROBE_TEXT = "한글😀";

    /** The number of code points of {@link #ENCODING_PROBE_TEXT}, which the probe query has to answer where the database counts code points. */
    public static final int ENCODING_PROBE_CODE_POINTS = 3;

    /** The encoding probe of the actual database; see {@link #generateEncodingProbe(Database)}. */
    public String generateEncodingProbe() {
        return generateEncodingProbe(database());
    }

    /**
     * The probe that proves that the database stores UTF-8 (JDBC has no call for the encoding and the standard views differ: one lists
     * every character set the product supports, another does not exist). The caller binds {@link #ENCODING_PROBE_TEXT} to both
     * placeholders; the query gives back the length and the value, and the database judges nothing: the caller refuses an answer whose
     * length is not {@link #ENCODING_PROBE_CODE_POINTS} (where {@link #countsCodePoints(Database)}) or whose value is not the text sent.
     * Every query casts the bind value to a character type of the database, so the value passes through the database and not only
     * through the driver. What that proves differs by product: where the character set is one property of the database, the
     * database itself; for MariaDB and MySQL the cast to CHAR is made in the character set of the connection only, so the first probe
     * proves the connection and not the default character set of the schema or of the server, which the columns of the tables get;
     * that is what {@link #generateSchemaCharsetProbe(Database)} is for.
     */
    public static String generateEncodingProbe(Database database) {
        // PostgreSQL: CHAR_LENGTH: https://www.postgresql.org/docs/current/functions-string.html
        // H2: CHAR_LENGTH: https://h2database.com/html/functions.html#char_length
        // Oracle: LENGTH counts characters, CAST to VARCHAR2: https://docs.oracle.com/en/database/oracle/oracle-database/23/sqlrf/LENGTH.html
        //         https://docs.oracle.com/en/database/oracle/oracle-database/23/sqlrf/CAST.html (DUAL: a SELECT has a FROM)
        // Db2: CHARACTER_LENGTH(... USING CODEUNITS32) counts code points: https://www.ibm.com/docs/en/db2/11.5?topic=functions-character-length
        //      (SYSIBM.SYSDUMMY1: a SELECT has a FROM)
        // SQL Server: LEN, CAST to VARCHAR (the code page of the collation): https://learn.microsoft.com/en-us/sql/t-sql/functions/len-transact-sql
        // MariaDB: CHAR_LENGTH, CAST AS CHAR: https://mariadb.com/kb/en/char_length/
        // MySQL: CHAR_LENGTH, CAST AS CHAR: https://dev.mysql.com/doc/refman/8.4/en/string-functions.html#function_char-length
        return switch (database) {
            case POSTGRESQL, H2 -> "SELECT CHAR_LENGTH(CAST(? AS VARCHAR(40))), CAST(? AS VARCHAR(40))";
            case MARIADB, MYSQL -> "SELECT CHAR_LENGTH(CAST(? AS CHAR(40))), CAST(? AS CHAR(40))";
            case ORACLE -> "SELECT LENGTH(CAST(? AS VARCHAR2(40))), CAST(? AS VARCHAR2(40)) FROM DUAL";
            case DB2 -> "SELECT CHARACTER_LENGTH(CAST(? AS VARCHAR(40)) USING CODEUNITS32), CAST(? AS VARCHAR(40)) FROM SYSIBM.SYSDUMMY1";
            case SQLSERVER -> "SELECT LEN(CAST(? AS VARCHAR(40))), CAST(? AS VARCHAR(40))";
        };
    }

    /** Whether the length of the actual database's probe counts code points; see {@link #countsCodePoints(Database)}. */
    public boolean countsCodePoints() {
        return countsCodePoints(database());
    }

    /**
     * Whether the length that {@link #generateEncodingProbe(Database)} gives is the number of code points, so that the probe text is
     * 3. It is not for H2, whose CHAR_LENGTH counts UTF-16 code units (the emoji counts 2, the probe text 4): there only the value
     * proves the encoding. The difference is a property of the product, so it is decided here with the query and not in the caller.
     * H2 is refused as the ledger database ({@code EmbeddedDatabaseRefusal}), so the product's {@code migrate} never reaches this case;
     * it stays so that the switch has a row for every {@link Database}.
     */
    public static boolean countsCodePoints(Database database) {
        // H2: CHAR_LENGTH: https://h2database.com/html/functions.html#char_length
        //   the documentation does not say what it counts; that it counts UTF-16 code units is the H2 implementation (the length of a
        //   Java String) and has not been run here: H2 is not on the test class path of this module (the build allows its driver on
        //   the seed bundle and the command line only)
        // every other product: the number of characters, which are code points in a UTF-8 database (see the documentation cited by
        // generateEncodingProbe)
        return switch (database) {
            case H2 -> false;
            case POSTGRESQL, ORACLE, DB2, SQLSERVER, MARIADB, MYSQL -> true;
        };
    }

    /** The probe of the character set of the schema of the actual database, if its product has one; see {@link #generateSchemaCharsetProbe(Database)}. */
    public Optional<String> generateSchemaCharsetProbe() {
        return generateSchemaCharsetProbe(database());
    }

    /**
     * The second probe, only for the products whose character set is a property of the schema and of the server as well as of the
     * connection: the character set that a table gets by default (the long text column of the schema files is created with it, and
     * the probe of {@link #generateEncodingProbe(Database)} passes through the connection, which may convert). It gives the default
     * character set of the schema, the character set of the server and, as the third column, 1 when both are {@code utf8mb4} and 0
     * otherwise; the database compares the names (any case), the caller reads the third column only and puts the first two in the
     * refusal. A product without this probe gives an empty result: the encoding is then proved by the first probe alone. The schema it
     * looks at is the database of the connection (the one {@code DATABASE()} names, the database of the JDBC URL); where the connection
     * has none the query gives no row, which the caller refuses.
     */
    public static Optional<String> generateSchemaCharsetProbe(Database database) {
        // MariaDB, MySQL: the schema (SCHEMATA) and the server variable report the character set by name, and a name may come in any
        //   case, so the database compares LOWER(name) and the caller does not compare strings:
        //   https://dev.mysql.com/doc/refman/8.4/en/case-sensitivity.html
        //   https://dev.mysql.com/doc/refman/8.4/en/charset-collation-coercibility.html
        // PostgreSQL, H2, Oracle, Db2, SQL Server: the character set is one property of the database that the first probe proves.
        return switch (database) {
            case MARIADB, MYSQL -> Optional.of("SELECT DEFAULT_CHARACTER_SET_NAME, @@character_set_server, "
                    + "CASE WHEN LOWER(DEFAULT_CHARACTER_SET_NAME) = 'utf8mb4' AND LOWER(@@character_set_server) = 'utf8mb4' "
                    + "THEN 1 ELSE 0 END FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = DATABASE()");
            case POSTGRESQL, H2, ORACLE, DB2, SQLSERVER -> Optional.empty();
        };
    }

    /** Appends the dialect's row limit to an otherwise complete query (it must end with an ORDER BY). */
    public String applyLimit(String statement, String parameter) {
        return statement + " " + generateLimit(parameter);
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
