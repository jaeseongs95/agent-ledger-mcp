package org.pgledger.database.schema;

import java.util.List;
import org.pgledger.database.repository.SqlBuilder;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;

/**
 * The encoding of the database has to be UTF-8: the schema files and the texts of the ledger are Unicode, and the digest of a file
 * is of its UTF-8 bytes. The database is not asked for the name of its character set (the standard views differ by product, JDBC has
 * no call for it, and a name is spelled differently by each product); it is asked to give back a text that only a UTF-8 database can
 * store, {@link SqlBuilder#ENCODING_PROBE_TEXT} (three code points: two Korean letters and an emoji outside the Basic Multilingual
 * Plane), through the probe query of the dialect of {@link SqlBuilder}. The queries and what differs by product are the builder's;
 * this class holds the two judgements, which are functions of what the database answered (the migrator asks the repository and
 * calls them), so that no name of a character set is compared in Java.
 */
public final class DatabaseEncoding {

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
}
