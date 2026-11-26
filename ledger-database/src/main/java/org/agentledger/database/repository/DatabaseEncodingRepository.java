// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.database.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Asks the database the questions that prove its encoding. The queries are the text of the dialect of {@link SqlBuilder} and come from
 * it only; this class binds the probe text, runs them and returns what the database answered. It judges nothing: the judgement is
 * {@code DatabaseEncoding} (the migrator calls both). Like the other repositories it takes the {@link JdbcClient} of the container and
 * joins the transaction of its caller, if there is one (principle 4.4).
 */
@Repository
public class DatabaseEncodingRepository {

    /**
     * What the database answered to the encoding probe: the length of the probe text and the text.
     *
     * @param length the length the database counted (code points, or UTF-16 code units for a product that says so in
     *        {@link SqlBuilder#countsCodePoints(SqlBuilder.Database)})
     * @param value the text the database gave back, null if it gave none
     */
    public record EncodingProbe(long length, String value) {
    }

    /**
     * What the database answered to the probe of the schema character set (a product that has one).
     *
     * @param schemaCharset the default character set of the schema, as the database names it
     * @param serverCharset the character set of the server, as the database names it
     * @param verdict the third column: 1 when the database found both to be UTF-8, 0 when not
     */
    public record SchemaCharsets(String schemaCharset, String serverCharset, int verdict) {
    }

    private final JdbcClient jdbc;
    private final SqlBuilder sqlBuilder;

    public DatabaseEncodingRepository(JdbcClient jdbc, SqlBuilder sqlBuilder) {
        this.jdbc = jdbc;
        this.sqlBuilder = sqlBuilder;
    }

    /** Sends {@link SqlBuilder#ENCODING_PROBE_TEXT} through the probe query of the dialect and returns the length and the value it came back with. */
    public EncodingProbe encodingProbe() {
        return jdbc.sql(sqlBuilder.generateEncodingProbe())
                .param(SqlBuilder.ENCODING_PROBE_TEXT)
                .param(SqlBuilder.ENCODING_PROBE_TEXT)
                .query((rs, row) -> new EncodingProbe(rs.getLong(1), rs.getString(2)))
                .single();
    }

    /**
     * The rows of the schema character set probe, or empty when the product of the database has none (nothing is asked then). A product
     * that has the probe gives a list: it is empty when the connection has no current schema or the database has no such schema, which
     * the caller refuses ({@code DatabaseEncoding.requireUtf8Schema}).
     */
    public Optional<List<SchemaCharsets>> schemaCharsets() {
        return sqlBuilder.generateSchemaCharsetProbe().map(sql -> jdbc.sql(sql)
                .query((rs, row) -> new SchemaCharsets(rs.getString(1), rs.getString(2), rs.getInt(3)))
                .list());
    }
}
