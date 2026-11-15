package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.database.repository.SchemaHistoryRepository;
import org.pgledger.database.repository.SchemaHistoryRepository.TypeInfo;
import org.pgledger.database.schema.LongTextType;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;

/**
 * RUN-001 .. RUN-003: the long text type that the placeholder {@code ${long_text}} of the schema files stands for is chosen from
 * what the driver says in {@code getTypeInfo}, in one place ({@link LongTextType}). No database here: the driver is a stub whose
 * type info is the rows of the test, so the same rows are given with and without a long character type. (RUN-004, in
 * {@code SchemaMigratorTest}, asks the driver of the test PostgreSQL.)
 */
class LongTextTypeTest {

    /** A stub {@link DatabaseMetaData} whose {@code getTypeInfo} has these rows: name, JDBC type, precision. */
    private static DatabaseMetaData driverWith(TypeInfo... rows) throws SQLException {
        ResultSet typeInfo = mock(ResultSet.class);
        AtomicInteger current = new AtomicInteger(-1);
        when(typeInfo.next()).thenAnswer(call -> current.incrementAndGet() < rows.length);
        when(typeInfo.getString("TYPE_NAME")).thenAnswer(call -> rows[current.get()].name());
        when(typeInfo.getInt("DATA_TYPE")).thenAnswer(call -> rows[current.get()].dataType());
        when(typeInfo.getLong("PRECISION")).thenAnswer(call -> rows[current.get()].precision());
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(metaData.getTypeInfo()).thenReturn(typeInfo);
        return metaData;
    }

    private static final TypeInfo INTEGER = new TypeInfo("int4", Types.INTEGER, 10);
    private static final TypeInfo SHORT_VARCHAR = new TypeInfo("varchar255", Types.VARCHAR, 255);

    @Test
    @DisplayName("RUN-001 a type of the JDBC long character type is the long text type, also when a VARCHAR type holds more")
    void run001_theLongCharacterTypeOfTheDriver() throws SQLException {
        DatabaseMetaData driver = driverWith(INTEGER, new TypeInfo("varchar", Types.VARCHAR, 10_485_760),
                new TypeInfo("CLOBTEXT", Types.LONGVARCHAR, 0), new TypeInfo("OTHERLONG", Types.LONGVARCHAR, 5));

        assertThat(LongTextType.choose(SchemaHistoryRepository.typeInfo(driver))).as("the first one the driver gave")
                .isEqualTo("CLOBTEXT");
    }

    @Test
    @DisplayName("RUN-002 a driver without the long character type: the VARCHAR type that holds the most characters, if it holds at least 32768; the first one on a tie")
    void run002_theLongestVarchar() throws SQLException {
        DatabaseMetaData driver = driverWith(new TypeInfo("name", Types.VARCHAR, 63), INTEGER,
                new TypeInfo("firstmax", Types.VARCHAR, 2_147_483_647L), new TypeInfo("varchar", Types.VARCHAR, 10_485_760),
                new TypeInfo("secondmax", Types.VARCHAR, 2_147_483_647L));

        assertThat(LongTextType.choose(SchemaHistoryRepository.typeInfo(driver))).isEqualTo("firstmax");
        assertThat(LongTextType.choose(List.of(new TypeInfo("just", Types.VARCHAR, LongTextType.MINIMUM_LENGTH))))
                .as("exactly the least length is enough").isEqualTo("just");
    }

    @Test
    @DisplayName("RUN-003 a driver without a long character type fails with SCHEMA_LONG_TEXT_TYPE_MISSING, whose English and Korean text name the missing type")
    void run003_noLongCharacterType() throws SQLException {
        List<DatabaseMetaData> drivers = List.of(driverWith(), driverWith(INTEGER, SHORT_VARCHAR),
                driverWith(new TypeInfo("almost", Types.VARCHAR, LongTextType.MINIMUM_LENGTH - 1L)),
                // a name that is not a plain type name is never put into the DDL, even for the right JDBC type
                driverWith(new TypeInfo("text; DROP TABLE x", Types.LONGVARCHAR, 0), new TypeInfo(null, Types.VARCHAR, 100_000)));

        for (DatabaseMetaData driver : drivers) {
            List<TypeInfo> types = SchemaHistoryRepository.typeInfo(driver);
            InfrastructureException failure = catchThrowableOfType(InfrastructureException.class,
                    () -> LongTextType.choose(types));

            assertThat(failure).as("the type info %s", types).isNotNull();
            assertThat(failure.code()).isEqualTo(InfrastructureCode.SCHEMA_LONG_TEXT_TYPE_MISSING);
            for (Locale locale : List.of(Locale.ROOT, Locale.KOREAN)) {
                String text = new MessageFormat(ResourceBundle.getBundle("messages/ledger-errors", locale)
                        .getString(failure.code().messageKey()), locale).format(failure.args().toArray());
                assertThat(text).as("the text in %s", locale.toLanguageTag()).contains("LONGVARCHAR")
                        .contains(String.valueOf(LongTextType.MINIMUM_LENGTH));
            }
        }
    }

    @Test
    @DisplayName("RUN-001 the rows are read as the driver gives them: TYPE_NAME, DATA_TYPE and PRECISION of every row of getTypeInfo")
    void run001_theRowsOfTheTypeInfo() throws SQLException {
        assertThat(SchemaHistoryRepository.typeInfo(driverWith(INTEGER, SHORT_VARCHAR)))
                .containsExactly(INTEGER, SHORT_VARCHAR);
    }
}
