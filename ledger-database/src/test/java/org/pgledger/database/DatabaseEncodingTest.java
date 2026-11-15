package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.text.MessageFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgledger.database.schema.DatabaseEncoding;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;

/**
 * RUN-017: the encoding of the database has to be UTF-8; what the database reports comes from the standard view
 * {@code INFORMATION_SCHEMA.CHARACTER_SETS} (read in {@code SchemaMigratorTest}), and the judgement of the names is this class.
 * No database here.
 */
class DatabaseEncodingTest {

    @ParameterizedTest
    @ValueSource(strings = {"UTF8", "UTF-8", "utf8", "Utf_8", "utf-8"})
    @DisplayName("RUN-017 UTF-8 in any spelling of case and separators is accepted")
    void utf8IsAccepted(String name) {
        DatabaseEncoding.requireUtf8(List.of(name));
    }

    @ParameterizedTest
    @ValueSource(strings = {"LATIN1", "SQL_ASCII", "WIN1252", "EUC_KR", "UTF16", "UTF-8BOM", ""})
    @DisplayName("RUN-017 another encoding is refused with SCHEMA_ENCODING_NOT_UTF8 naming what the database reports, in English and in Korean")
    void anotherEncodingIsRefused(String name) {
        InfrastructureException refused = catchThrowableOfType(InfrastructureException.class,
                () -> DatabaseEncoding.requireUtf8(List.of(name)));

        assertThat(refused).isNotNull();
        assertThat(refused.code()).isEqualTo(InfrastructureCode.SCHEMA_ENCODING_NOT_UTF8);
        assertThat(refused.args()).containsExactly(name);
        for (Locale locale : List.of(Locale.ROOT, Locale.KOREAN)) {
            String text = new MessageFormat(ResourceBundle.getBundle("messages/ledger-errors", locale)
                    .getString(refused.code().messageKey()), locale).format(refused.args().toArray());
            assertThat(text).as(locale.toLanguageTag()).contains(name).contains("UTF-8");
        }
    }

    @Test
    @DisplayName("RUN-017 a database that reports no character set, a null name, or one name that is not UTF-8 among others is refused")
    void nothingOrOneWrongNameIsRefused() {
        for (List<String> reported : List.of(List.<String>of(), Arrays.asList("UTF8", null), List.of("UTF8", "LATIN1"))) {
            InfrastructureException refused = catchThrowableOfType(InfrastructureException.class,
                    () -> DatabaseEncoding.requireUtf8(reported));

            assertThat(refused).as("%s", reported).isNotNull();
            assertThat(refused.code()).isEqualTo(InfrastructureCode.SCHEMA_ENCODING_NOT_UTF8);
        }
        assertThat(catchThrowableOfType(InfrastructureException.class, () -> DatabaseEncoding.requireUtf8(List.of())).args())
                .containsExactly("none");
    }
}
