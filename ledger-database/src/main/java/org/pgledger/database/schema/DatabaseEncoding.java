package org.pgledger.database.schema;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.pgledger.database.repository.DatabaseEncodingRepository.SchemaCharsets;
import org.pgledger.database.repository.SqlBuilder;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;

/**
 * The encoding of the database has to be UTF-8: the schema files and the texts of the ledger are Unicode, and the digest of a file
 * is of its UTF-8 bytes. The database is not asked for the name of its character set (the standard views differ by product, JDBC has
 * no call for it, and a name is spelled differently by each product); it is asked to give back a text that only a UTF-8 database can
 * store, {@link SqlBuilder#ENCODING_PROBE_TEXT} (three code points: two Korean letters and an emoji outside the Basic Multilingual
 * Plane), through the probe query of the dialect of {@link SqlBuilder}. The queries and what differs by product are the builder's;
 * this class holds the judgements, which are functions of what the database answered (the migrator asks the repository and
 * calls them), so that no name of a character set is compared in Java, and the reading of a failed probe (a SQLSTATE of the class 22).
 */
public final class DatabaseEncoding {

    /** The first two characters of the SQLSTATE of a data exception, the class 22 of the SQL standard (the same for every product). */
    private static final String DATA_EXCEPTION_CLASS = "22";

    private DatabaseEncoding() {
    }

    /**
     * Judges the answer of the encoding probe: the length the database counted and the text it gave back for
     * {@link SqlBuilder#ENCODING_PROBE_TEXT}.
     *
     * @param countsCodePoints whether the length is a number of code points ({@link SqlBuilder#countsCodePoints(SqlBuilder.Database)});
     *        when it is not, the length is not compared and the value alone decides
     * @param length the length the database counted
     * @param value the text the database gave back, null if none
     * @throws InfrastructureException SCHEMA_ENCODING_NOT_UTF8 when the length is not
     *         {@link SqlBuilder#ENCODING_PROBE_CODE_POINTS} (where code points are counted) or the value is not the probe text; the
     *         argument names what the database answered
     */
    public static void requireUtf8(boolean countsCodePoints, long length, String value) {
        boolean lengthRight = !countsCodePoints || length == SqlBuilder.ENCODING_PROBE_CODE_POINTS;
        boolean valueRight = SqlBuilder.ENCODING_PROBE_TEXT.equals(value);
        if (!lengthRight || !valueRight) {
            throw new InfrastructureException(InfrastructureCode.SCHEMA_ENCODING_NOT_UTF8,
                    List.of("length=" + length + ", value=" + value));
        }
    }

    /**
     * Judges the answer of the schema character set probe (MariaDB, MySQL): only the third column decides, and the database made it
     * (it compared the names itself); the two names are for the refusal.
     *
     * @param schemaCharset the default character set of the schema, as the database names it
     * @param serverCharset the character set of the server, as the database names it
     * @param verdict 1 when the database found both to be UTF-8, 0 when not
     * @throws InfrastructureException SCHEMA_ENCODING_NOT_UTF8 when the verdict is not 1; the argument names the two character sets
     */
    public static void requireUtf8Schema(String schemaCharset, String serverCharset, int verdict) {
        if (verdict != 1) {
            throw new InfrastructureException(InfrastructureCode.SCHEMA_ENCODING_NOT_UTF8,
                    List.of("schema character set=" + schemaCharset + ", server character set=" + serverCharset));
        }
    }

    /**
     * Judges what the schema character set probe returned as a list of rows: no row means that the database names no schema for the
     * connection ({@code DATABASE()} is null, or the schema does not exist), so nothing proves the character set and it is refused;
     * otherwise every row is judged by its third column.
     *
     * @throws InfrastructureException SCHEMA_ENCODING_NOT_UTF8 for no row (the argument is {@code schema character set=none}) or a row
     *         whose verdict is not 1
     */
    public static void requireUtf8Schema(List<SchemaCharsets> rows) {
        if (rows.isEmpty()) {
            throw new InfrastructureException(InfrastructureCode.SCHEMA_ENCODING_NOT_UTF8,
                    List.of("schema character set=none"));
        }
        rows.forEach(row -> requireUtf8Schema(row.schemaCharset(), row.serverCharset(), row.verdict()));
    }

    /**
     * The SQLSTATE of the failure when it is of the class 22, the data exceptions of the SQL standard (a character that has no
     * equivalent in the character set of the database, a character outside its repertoire, and the like), whatever the product's own
     * code is: only the first two characters are looked at. Which state counts: the causes are followed to the first
     * {@link SQLException}, the main one; the first SQLSTATE that is not null among that exception and its next exceptions is the
     * state of the failure, and it is returned only if it is of the class 22. So a failure whose state is another one is not an
     * encoding problem even when a later exception of its chain has the class 22, and a failure with no SQLSTATE is none. Neither walk
     * visits an exception twice, so a loop ends it.
     *
     * <p>A limit: a character set error whose SQLSTATE is not of the class 22 (MySQL answers some with {@code HY000}) is not
     * recognised here and is propagated as it is; the probe runs before any DDL, so nothing has been changed then.
     *
     * @return the SQLSTATE, empty when the state of the failure is not of the class 22 or there is none
     */
    public static Optional<String> dataExceptionState(Throwable failure) {
        SQLException main = firstSqlException(failure);
        return main == null ? Optional.empty() : Optional.ofNullable(firstState(main))
                .filter(state -> state.startsWith(DATA_EXCEPTION_CLASS));
    }

    /** The first {@link SQLException} in the chain of causes (starting with {@code failure} itself), null if there is none. */
    private static SQLException firstSqlException(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable link = failure; link != null && seen.add(link); link = link.getCause()) {
            if (link instanceof SQLException sql) {
                return sql;
            }
        }
        return null;
    }

    /** The first SQLSTATE that is not null of {@code main} and its next exceptions, null if there is none. */
    private static String firstState(SQLException main) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (SQLException next = main; next != null && seen.add(next); next = next.getNextException()) {
            if (next.getSQLState() != null) {
                return next.getSQLState();
            }
        }
        return null;
    }

    /**
     * What to throw for a failure of a probe query: the probe asks the database to store a text, so a data exception (SQLSTATE class 22,
     * {@link #dataExceptionState}) is the database saying that it cannot, an encoding problem, and is refused as such with the failure
     * as the cause; every other failure (a lost connection, a missing right) is returned unchanged for the caller to propagate.
     *
     * @param failure the failure of the probe query
     * @return an InfrastructureException SCHEMA_ENCODING_NOT_UTF8 (argument {@code SQLSTATE=<state>}, cause {@code failure}), or
     *         {@code failure} itself
     */
    public static RuntimeException probeFailure(RuntimeException failure) {
        return dataExceptionState(failure).<RuntimeException>map(state -> new InfrastructureException(
                InfrastructureCode.SCHEMA_ENCODING_NOT_UTF8, List.of("SQLSTATE=" + state), failure)).orElse(failure);
    }
}
