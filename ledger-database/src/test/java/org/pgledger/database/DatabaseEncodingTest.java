package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

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
import org.pgledger.database.repository.SqlBuilder;
import org.pgledger.database.schema.DatabaseEncoding;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;

/**
 * RUN-026, RUN-027, RUN-028: the judgement of what a database answered to the encoding probe (defect D-0047). The judgements are
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
            assertThat(text).as(locale.toLanguageTag()).contains(refused.args().getFirst()).contains("UTF-8");
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
}
