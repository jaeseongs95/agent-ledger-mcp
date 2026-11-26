// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.sql.SQLException;
import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.veridger.database.repository.DatabaseEncodingRepository.SchemaCharsets;
import org.veridger.database.repository.SqlBuilder;
import org.veridger.database.schema.DatabaseEncoding;
import org.veridger.domain.error.InfrastructureCode;
import org.veridger.domain.error.InfrastructureException;
import org.springframework.jdbc.UncategorizedSQLException;

/**
 * RUN-026, RUN-027, RUN-028, RUN-032: the judgement of what a database answered to the encoding probe (defect D-0047). The judgements are
 * functions of the answer, so the refusals are given the answers of a database that is not UTF-8 as values; no database here and
 * nothing replaced on the real path (the probe itself, on the test PostgreSQL, is RUN-029 in {@code SchemaMigratorTest}).
 */
class DatabaseEncodingTest {

    private static final String PROBE = SqlBuilder.ENCODING_PROBE_TEXT;

    private static InfrastructureException refusal(Runnable judgement) {
        InfrastructureException refused = catchThrowableOfType(InfrastructureException.class, judgement::run);
        assertThat(refused).as("a refusal").isNotNull();
        assertThat(refused.code()).isEqualTo(InfrastructureCode.SCHEMA_ENCODING_NOT_UTF8);
        return refused;
    }

    private static void assertTextsNameWhatTheDatabaseAnswered(InfrastructureException refused) {
        assertThat(refused.args()).hasSize(1);
        for (Locale locale : List.of(Locale.ROOT, Locale.KOREAN)) {
            String text = new MessageFormat(ResourceBundle.getBundle("messages/ledger-errors", locale)
                    .getString(refused.code().messageKey()), locale).format(refused.args().toArray());
            assertThat(text).as(locale.toLanguageTag()).contains(refused.args().getFirst()).contains("UTF-8")
                    .doesNotContainIgnoringCase("postgres").doesNotContainIgnoringCase("mysql").doesNotContainIgnoringCase("mariadb")
                    .doesNotContainIgnoringCase("oracle").doesNotContainIgnoringCase("db2").doesNotContainIgnoringCase("sql server");
        }
    }

    @Test
    @DisplayName("RUN-026 the probe text of three code points is two Korean letters and one emoji outside the BMP, 10 bytes in UTF-8")
    void run026_theProbeTextIsWhatTheDecisionSays() {
        assertThat(PROBE).isEqualTo("한글😀");
        assertThat(PROBE.codePointCount(0, PROBE.length())).isEqualTo(SqlBuilder.ENCODING_PROBE_CODE_POINTS).isEqualTo(3);
        assertThat(PROBE.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(10);
        assertThat(PROBE.length()).as("UTF-16 code units").isEqualTo(4);
    }

    @Test
    @DisplayName("RUN-026 a UTF-8 database answers the length 3 and the text, and is accepted")
    void run026_theAnswerOfAUtf8DatabaseIsAccepted() {
        DatabaseEncoding.requireUtf8(true, 3, PROBE);
    }

    @Test
    @DisplayName("RUN-026 where the length counts UTF-16 code units only the value is compared: 4 is accepted, and so is any length")
    void run026_aDatabaseThatCountsCodeUnitsIsJudgedByTheValueAlone() {
        DatabaseEncoding.requireUtf8(false, 4, PROBE);
        DatabaseEncoding.requireUtf8(false, 3, PROBE);
        DatabaseEncoding.requireUtf8(false, 10, PROBE);
    }

    /** The answers of a database that is not UTF-8, for a database that counts code points. */
    static Stream<Arguments> refusedAnswers() {
        return Stream.of(
                Arguments.of(10L, PROBE),
                Arguments.of(4L, PROBE),
                Arguments.of(2L, PROBE),
                Arguments.of(0L, PROBE),
                Arguments.of(3L, "??😀"),
                Arguments.of(3L, "한글?"),
                Arguments.of(3L, "????"),
                Arguments.of(10L, "????"),
                Arguments.of(3L, "한글"),
                Arguments.of(3L, ""),
                Arguments.of(3L, null),
                Arguments.of(3L, "���"));
    }

    @ParameterizedTest
    @MethodSource("refusedAnswers")
    @DisplayName("RUN-027 a length other than 3 or a value other than the probe text is SCHEMA_ENCODING_NOT_UTF8, in English and in Korean")
    void run027_anotherLengthOrAnotherValueIsRefused(long length, String value) {
        InfrastructureException refused = refusal(() -> DatabaseEncoding.requireUtf8(true, length, value));

        assertThat(refused.args().getFirst()).contains("length=" + length).contains("value=" + value);
        assertTextsNameWhatTheDatabaseAnswered(refused);
    }

    @ParameterizedTest
    @MethodSource("refusedValues")
    @DisplayName("RUN-027 where the length is not compared a wrong value is refused all the same, with any length")
    void run027_aWrongValueIsRefusedWhereTheLengthIsIgnored(String value) {
        for (long length : new long[] {2, 3, 4, 10}) {
            refusal(() -> DatabaseEncoding.requireUtf8(false, length, value));
        }
    }

    static Stream<String> refusedValues() {
        return Stream.of("??😀", "한글?", "????", "", null);
    }

    @Test
    @DisplayName("RUN-028 the third column decides: 1 is accepted, a 0 names the two character sets in English and in Korean")
    void run028_onlyTheThirdColumnDecides() {
        DatabaseEncoding.requireUtf8Schema("utf8mb4", "utf8mb4", 1);
        // the names are for the refusal only: nothing is compared in Java, so what the database made of them stands
        DatabaseEncoding.requireUtf8Schema("whatever", "names", 1);

        for (String[] names : new String[][] {{"latin1", "utf8mb4"}, {"utf8mb4", "latin1"}, {"utf8mb3", "utf8mb4"}, {"utf8mb4", "utf8mb4"}}) {
            InfrastructureException refused = refusal(() -> DatabaseEncoding.requireUtf8Schema(names[0], names[1], 0));
            assertThat(refused.args().getFirst()).contains(names[0]).contains(names[1]);
            assertTextsNameWhatTheDatabaseAnswered(refused);
        }
        InfrastructureException unnamed = refusal(() -> DatabaseEncoding.requireUtf8Schema(null, null, 0));
        assertThat(unnamed.args().getFirst()).contains("null");
        refusal(() -> DatabaseEncoding.requireUtf8Schema("utf8mb4", "utf8mb4", 2));
        refusal(() -> DatabaseEncoding.requireUtf8Schema("utf8mb4", "utf8mb4", -1));
    }

    @Test
    @DisplayName("RUN-028 the rows of the schema probe: no row is refused (schema character set=none), a row that is not 1 is refused, every row must be accepted")
    void run028_noRowIsRefusedAndEveryRowIsJudged() {
        InfrastructureException none = refusal(() -> DatabaseEncoding.requireUtf8Schema(List.of()));
        assertThat(none.args()).containsExactly("schema character set=none");
        assertTextsNameWhatTheDatabaseAnswered(none);

        DatabaseEncoding.requireUtf8Schema(List.of(new SchemaCharsets("utf8mb4", "utf8mb4", 1)));
        InfrastructureException refused = refusal(() -> DatabaseEncoding.requireUtf8Schema(List.of(
                new SchemaCharsets("utf8mb4", "utf8mb4", 1), new SchemaCharsets("latin1", "utf8mb4", 0))));
        assertThat(refused.args().getFirst()).contains("latin1");
    }

    /** A failure of a probe query: the SQLException wrapped the way Spring wraps it. */
    private static RuntimeException failedWith(SQLException cause) {
        return new UncategorizedSQLException("StatementCallback", "SELECT 1", cause);
    }

    @ParameterizedTest
    @ValueSource(strings = {"22P05", "22021", "22000", "22"})
    @DisplayName("RUN-032 a probe that fails with a SQLSTATE of the class 22 is SCHEMA_ENCODING_NOT_UTF8 naming the SQLSTATE, with the failure as cause")
    void run032_aDataExceptionIsAnEncodingRefusal(String state) {
        RuntimeException failure = failedWith(new SQLException("character with byte sequence 0xf0 0x9f 0x98 0x80 has no equivalent", state));

        RuntimeException result = DatabaseEncoding.probeFailure(failure);

        assertThat(DatabaseEncoding.dataExceptionState(failure)).contains(state);
        assertThat(result).isInstanceOfSatisfying(InfrastructureException.class, refused -> {
            assertThat(refused.code()).isEqualTo(InfrastructureCode.SCHEMA_ENCODING_NOT_UTF8);
            assertThat(refused.args()).containsExactly("SQLSTATE=" + state);
            assertThat(refused.getCause()).isSameAs(failure);
            assertTextsNameWhatTheDatabaseAnswered(refused);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"08006", "08001", "42601", "23505", "57014", "2", "HY000", ""})
    @DisplayName("RUN-032 any other SQLSTATE (a lost connection, a syntax error) is returned unchanged, to be propagated")
    void run032_anotherFailureIsPropagated(String state) {
        RuntimeException failure = failedWith(new SQLException("some failure", state));

        assertThat(DatabaseEncoding.dataExceptionState(failure)).isEmpty();
        assertThat(DatabaseEncoding.probeFailure(failure)).isSameAs(failure);
    }

    @Test
    @DisplayName("RUN-032 a null SQLSTATE and a failure with no SQLException are propagated; the main SQLException is the first one in the causes")
    void run032_theMainExceptionDecides() {
        RuntimeException noState = failedWith(new SQLException("no state"));
        RuntimeException noSql = new IllegalStateException("not a database failure");
        assertThat(DatabaseEncoding.probeFailure(noState)).isSameAs(noState);
        assertThat(DatabaseEncoding.probeFailure(noSql)).isSameAs(noSql);

        RuntimeException deep = new IllegalStateException("outer", new RuntimeException("middle", new SQLException("inner", "22021")));
        assertThat(DatabaseEncoding.dataExceptionState(deep)).contains("22021");
        assertThat(DatabaseEncoding.probeFailure(deep)).isInstanceOf(InfrastructureException.class);
    }

    @Test
    @DisplayName("RUN-032 the first SQLSTATE that is not null among the main exception and its next exceptions decides: 22 refuses, any other propagates")
    void run032_theFirstStateOfTheNextChainDecides() {
        // the main exception has no state: the first state that is not null is the one of its next exception
        SQLException noState = new SQLException("no state");
        noState.setNextException(new SQLException("second", "22P05"));
        assertThat(DatabaseEncoding.dataExceptionState(failedWith(noState))).contains("22P05");
        assertThat(DatabaseEncoding.probeFailure(failedWith(noState))).isInstanceOf(InfrastructureException.class);

        // another state first: a 22P05 later in the chain does not make it an encoding problem
        SQLException connection = new SQLException("first", "08006");
        connection.setNextException(new SQLException("second", "22P05"));
        RuntimeException lost = failedWith(connection);
        assertThat(DatabaseEncoding.dataExceptionState(lost)).isEmpty();
        assertThat(DatabaseEncoding.probeFailure(lost)).isSameAs(lost);

        // the cause of an SQLException is not a next exception: the HY000 decides
        SQLException wrapper = new SQLException("batch", "HY000", new SQLException("cause", "22021"));
        RuntimeException other = new IllegalStateException(wrapper);
        assertThat(DatabaseEncoding.dataExceptionState(other)).isEmpty();
        assertThat(DatabaseEncoding.probeFailure(other)).isSameAs(other);
    }

    @Test
    @DisplayName("RUN-032 the walk ends on a loop of next exceptions")
    void run032_aLoopOfNextExceptionsEndsTheWalk() {
        SQLException a = new SQLException("a", "08006");
        SQLException b = new SQLException("b", "08001");
        a.setNextException(b);
        b.setNextException(a);
        assertThat(DatabaseEncoding.dataExceptionState(failedWith(a))).isEmpty();
        assertThat(DatabaseEncoding.probeFailure(failedWith(a))).isInstanceOf(UncategorizedSQLException.class);

        SQLException unnamedA = new SQLException("a");
        SQLException unnamedB = new SQLException("b");
        unnamedA.setNextException(unnamedB);
        unnamedB.setNextException(unnamedA);
        assertThat(DatabaseEncoding.dataExceptionState(failedWith(unnamedA))).isEmpty();
    }

    @Test
    @DisplayName("RUN-032 the walk ends on a loop of causes (A to B to A), with and without an SQLException before the loop")
    void run032_aLoopOfCausesEndsTheWalk() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);
        assertThat(DatabaseEncoding.dataExceptionState(a)).isEmpty();
        assertThat(DatabaseEncoding.probeFailure(a)).isSameAs(a);

        RuntimeException c = new RuntimeException("c");
        RuntimeException d = new RuntimeException("d", c);
        c.initCause(d);
        RuntimeException outer = new IllegalStateException("outer", new RuntimeException("middle", c));
        assertThat(DatabaseEncoding.dataExceptionState(outer)).isEmpty();
        assertThat(DatabaseEncoding.probeFailure(outer)).isSameAs(outer);
    }
}
