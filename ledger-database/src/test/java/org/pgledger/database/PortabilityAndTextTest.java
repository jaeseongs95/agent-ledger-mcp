// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.database.repository.Counter;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.database.repository.IssueCaseRepository;
import org.pgledger.database.repository.IssueRepository;
import org.pgledger.database.support.DatabaseTest;
import org.pgledger.database.support.LedgerTestContext;
import org.pgledger.database.support.Seed;
import org.pgledger.domain.model.Issue;
import org.pgledger.domain.model.IssueCase;
import org.pgledger.domain.model.IssueFields;
import org.pgledger.domain.model.Project;
import org.pgledger.domain.value.IssueId;
import org.pgledger.domain.value.Patch;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * REPO-031, REPO-032 and REPO-033. REPO-037 (the statements are understood by a second DBMS) runs on the test server.
 */
@DatabaseTest
class PortabilityAndTextTest {

    private LedgerTestContext ctx;

    @BeforeEach
    void setUp(LedgerTestContext context) {
        ctx = context;
    }

    private record IssueText(String column, int characters, BiFunction<String, String, IssueFields> fields) {
        @Override
        public String toString() {
            return column;
        }
    }

    private static IssueFields issueFields(String project, String title, String symptom, String cause) {
        return IssueFields.of(Patch.set(project), Patch.set(title), Patch.set("PROCESS"), Patch.set("P1"),
                Patch.set(symptom), Patch.set(cause), Patch.absent());
    }

    static Stream<IssueText> longIssueTexts() {
        return Stream.of(
                new IssueText("title", 300, (p, t) -> issueFields(p, t, "b", "c")),
                new IssueText("symptom", 4000, (p, t) -> issueFields(p, "t", t, "c")),
                new IssueText("root_cause", 4000, (p, t) -> issueFields(p, "t", "b", t)));
    }

    @ParameterizedTest
    @MethodSource("longIssueTexts")
    @DisplayName("REPO-031: long Korean issue texts are stored and read back without loss")
    void longIssueTextsAreExact(IssueText text) {
        Project project = new Seed(ctx).project();
        Issue saved = LedgerTestContext.as(Seed.ALICE, () -> ctx.tx(() -> ctx.bean(IssueRepository.class).save(
                Issue.open(IssueId.of(project.code(), 1), text.fields().apply(project.id().value(),
                        "한".repeat(text.characters())), LedgerTestContext.START))));
        assertThat(ctx.bean(IssueRepository.class).findById(saved.id())).hasValue(saved);
    }

    private record CaseParts(String observation, String evidence, String quote) {
    }

    private record CaseText(String column, int characters, Function<String, CaseParts> parts) {
        @Override
        public String toString() {
            return column;
        }
    }

    static Stream<CaseText> longCaseTexts() {
        return Stream.of(
                new CaseText("observation", 4000, t -> new CaseParts(t, null, null)),
                new CaseText("evidence", 2000, t -> new CaseParts("o", t, null)),
                new CaseText("source_quote", 2000, t -> new CaseParts("o", null, t)));
    }

    @ParameterizedTest
    @MethodSource("longCaseTexts")
    @DisplayName("REPO-031: long Korean case texts are stored and read back without loss")
    void longCaseTextsAreExact(CaseText text) {
        Seed seed = new Seed(ctx);
        Issue issue = seed.issue(seed.project(), 1, "case host", LedgerTestContext.START);
        CaseParts parts = text.parts().apply("한".repeat(text.characters()));
        IssueCase observed = ctx.tx(() -> ctx.bean(IssueCaseRepository.class).insert(IssueCase.observe(
                ctx.bean(IdAllocator.class).next(Counter.ISSUE_CASE), issue.id(), parts.observation(),
                parts.evidence(), parts.quote(), null, Seed.ALICE, LedgerTestContext.START)));
        assertThat(ctx.bean(IssueCaseRepository.class).findById(observed.id())).hasValue(observed);
    }

    @Test
    @DisplayName("REPO-032: an id with a lower-case letter never reaches the database")
    void lowerCaseIdsAreRefusedBeforeAnySql() {
        ctx.spy().clear();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> IssueId.of("abc-I-0001"))
                .isInstanceOf(org.pgledger.domain.error.ValidationException.class);
        assertThat(ctx.spy().statements()).isEmpty();
    }

    @Test
    @DisplayName("REPO-033: no text column of any table holds an empty string")
    void noColumnHoldsAnEmptyString() throws SQLException {
        Seed seed = new Seed(ctx);
        seed.issue(seed.project(), 1, "scan host", LedgerTestContext.START);
        JdbcClient jdbc = JdbcClient.create(ctx.pool());
        Set<String> textColumns = jdbc.sql("SELECT LOWER(table_name) || '.' || LOWER(column_name) FROM "
                + "information_schema.columns WHERE LOWER(data_type) IN ('character varying', 'text', "
                + "'character large object', 'clob', 'varchar') AND table_schema = CURRENT_SCHEMA")
                .query(String.class).set();
        assertThat(textColumns).contains("issue.title", "issue.symptom", "spec.title", "history.reason");
        Set<String> emptyOnes = textColumns.stream().filter(column -> jdbc.sql("SELECT COUNT(*) FROM "
                + column.substring(0, column.indexOf('.')) + " WHERE " + column.substring(column.indexOf('.') + 1)
                + " = ''").query(Integer.class).single() > 0).collect(Collectors.toSet());
        assertThat(emptyOnes).isEmpty();
    }
}
