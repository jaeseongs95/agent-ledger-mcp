// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.schema;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.veridger.domain.error.InfrastructureCode;
import org.veridger.domain.error.InfrastructureException;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies the grant template {@code db-templates/dml-grants.sql} to the application account: the account of the MCP server,
 * the admin web and the owner commands of the command line tool (they share one). The file holds ANSI {@code GRANT}
 * statements only (no DDL right, no {@code UPDATE} or {@code DELETE} on the history); creating the account is a statement
 * of the DBMS and stays an operator step done with the DBMS's own tools before the installation. It is run by the
 * operator's {@code migrate} command through the DDL account (the one that may grant), after the schema is applied and
 * again after every upgrade, so that a table a later schema file adds is granted too. Granting a right twice changes nothing.
 *
 * <p>The account name is the only value that enters the SQL text, so it must be a plain identifier ({@link #isAccountName}):
 * no quoting is needed and no text of the operator can become SQL. The statements run through Spring's
 * {@link ResourceDatabasePopulator}, as the schema files do (see {@link SchemaFileApplier}), on the ledger data source, whose
 * connections already use the schema of {@link SchemaProperties#name()} when one is configured. Reference:
 * https://docs.spring.io/spring-framework/reference/data-access/jdbc/embedded-database-support.html (script execution).
 *
 * <p>The grant step is one use case, so it is one transaction (principle 4.4): {@link #apply} is a {@code @Transactional}
 * method of a service, and the populator takes its connection from Spring's {@code DataSourceUtils}, so it joins that
 * transaction like the schema file of {@link SchemaFileApplier}. On a DBMS whose {@code GRANT} is transactional a statement that
 * fails takes the statements before it back (not checked here for any product: the DDL of the test PostgreSQL is transactional,
 * SCH-004, and that of its {@code GRANT} is assumed from it). Not every DBMS does that: MySQL lists {@code GRANT} among the statements that implicitly commit
 * (https://dev.mysql.com/doc/refman/8.4/en/implicit-commit.html, "Statements that implicitly use or modify tables in the
 * mysql database") and Oracle commits the current transaction before and after every DDL statement, {@code GRANT} being one
 * (https://docs.oracle.com/en/database/oracle/oracle-database/23/sqlrf/Types-of-SQL-Statements.html, "Data Definition
 * Language (DDL) Statements"). There a failure in the middle leaves part of the grants applied, however this class is
 * annotated; that is why the step is safe to run again: granting a privilege that the account has already adds it to those it
 * has (PostgreSQL: "These privileges are added to those already granted, if any",
 * https://www.postgresql.org/docs/current/sql-grant.html; the other products are not checked here, INS-010 runs the step twice
 * on the test PostgreSQL), and {@code migrate} and {@code setup} run it on every call, so the next run finishes what a failed
 * one left.
 */
@Service
@Transactional
public class GrantApplier {

    /** The class path location of the template. */
    public static final String TEMPLATE = "db-templates/dml-grants.sql";

    /** A plain unquoted identifier: a letter, then letters, digits or underscores, at most 30 characters in all. */
    private static final Pattern ACCOUNT = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,29}");

    /**
     * The words that SQL accepts in the place of the grantee without quotes and that name no account: {@code PUBLIC} is every
     * role, the others are the role of the session. They are plain identifiers by their form, but {@code GRANT ... TO public}
     * would give every role of the database the rights of the template.
     */
    private static final Set<String> NOT_AN_ACCOUNT = Set.of("PUBLIC", "CURRENT_USER", "SESSION_USER", "CURRENT_ROLE");
    private static final String PLACEHOLDER = "${dml_account}";

    private final DataSource dataSource;

    public GrantApplier(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Whether the text can be an account name in the template; no other text is ever put into the SQL. A plain unquoted
     * identifier that is not one of the words that stand for a group of accounts ({@code PUBLIC}, {@code CURRENT_USER},
     * {@code SESSION_USER}, {@code CURRENT_ROLE}, in any case).
     */
    public static boolean isAccountName(String text) {
        return text != null && ACCOUNT.matcher(text).matches() && !NOT_AN_ACCOUNT.contains(text.toUpperCase(Locale.ROOT));
    }

    /**
     * Grants the rights of the template to the account.
     *
     * @throws IllegalArgumentException when the text is not an account name (nothing is run)
     * @throws InfrastructureException SCHEMA_GRANT_FAILED when the database refuses a statement, for example because the
     *         account does not exist or the connection may not grant; the transaction is rolled back, which takes the
     *         statements before it back where the DBMS lets a grant be rolled back, and the next run repeats them where it
     *         does not (see the class comment)
     */
    public void apply(String account) {
        if (!isAccountName(account)) {
            throw new IllegalArgumentException("not an account name");
        }
        String sql;
        try {
            sql = new ClassPathResource(TEMPLATE, GrantApplier.class.getClassLoader())
                    .getContentAsString(StandardCharsets.UTF_8).replace(PLACEHOLDER, account);
        } catch (IOException e) {
            throw new UncheckedIOException("the grant template " + TEMPLATE + " cannot be read", e);
        }
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(false, false, "UTF-8",
                new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
        try {
            DatabasePopulatorUtils.execute(populator, dataSource);
        } catch (DataAccessException e) {
            // the account must exist and the connection must be allowed to grant
            throw new InfrastructureException(InfrastructureCode.SCHEMA_GRANT_FAILED, List.of(), e);
        }
    }
}
