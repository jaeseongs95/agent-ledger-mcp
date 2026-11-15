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
import java.util.ArrayList;
import java.util.Collections;
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
 * RUN-001 .. RUN-003, RUN-020, RUN-021: the declaration of the long text type that the placeholder {@code ${long_text}} of the schema
 * files stands for is chosen from what the driver says in {@code getTypeInfo}, in one place ({@link LongTextType}). No database here: the
 * driver is a stub whose type info is the rows of the test. (RUN-004, in {@code SchemaMigratorTest}, asks the driver of the test
 * PostgreSQL.)
 */
class LongTextTypeTest {

    private static final int MAX = Integer.MAX_VALUE;

    /** A stub {@link DatabaseMetaData} whose {@code getTypeInfo} has these rows: name, JDBC type, precision, create params. */
    private static DatabaseMetaData driverWith(TypeInfo... rows) throws SQLException {
        ResultSet typeInfo = mock(ResultSet.class);
        AtomicInteger current = new AtomicInteger(-1);
        when(typeInfo.next()).thenAnswer(call -> current.incrementAndGet() < rows.length);
        when(typeInfo.getString("TYPE_NAME")).thenAnswer(call -> rows[current.get()].name());
        when(typeInfo.getInt("DATA_TYPE")).thenAnswer(call -> rows[current.get()].dataType());
        when(typeInfo.getLong("PRECISION")).thenAnswer(call -> rows[current.get()].precision());
        when(typeInfo.getString("CREATE_PARAMS")).thenAnswer(call -> rows[current.get()].createParams());
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(metaData.getTypeInfo()).thenReturn(typeInfo);
        return metaData;
    }

    private static final TypeInfo INTEGER = new TypeInfo("int4", Types.INTEGER, 10, null);
    private static final TypeInfo SHORT_VARCHAR = new TypeInfo("varchar255", Types.VARCHAR, 255, "max length");

    /** The choice must not depend on the order of the rows: it is made for the rows as given and for the same rows reversed. */
    private static String chosen(TypeInfo... rows) {
        List<TypeInfo> given = List.of(rows);
        List<TypeInfo> reversed = new ArrayList<>(given);
        Collections.reverse(reversed);
        String declaration = LongTextType.choose(given);
        assertThat(LongTextType.choose(reversed)).as("the same rows in the other order").isEqualTo(declaration);
        return declaration;
    }

    @Test
    @DisplayName("RUN-001 a large object type that needs no length is the long text type, before a CLOB or LONGVARCHAR with parameters and before a VARCHAR that holds more")
    void run001_theLargeObjectTypeOfTheDriver() throws SQLException {
        DatabaseMetaData driver = driverWith(INTEGER, new TypeInfo("varchar", Types.VARCHAR, 10_485_760, "max length"),
                new TypeInfo("BIGCLOB", Types.CLOB, MAX, "LENGTH"), new TypeInfo("LONGWITH", Types.LONGVARCHAR, MAX, "LENGTH"),
                new TypeInfo("SMALLLONG", Types.LONGVARCHAR, 5, null), new TypeInfo("OTHERLONG", Types.LONGVARCHAR, 9, ""));

        assertThat(chosen(SchemaHistoryRepository.typeInfo(driver).toArray(TypeInfo[]::new)))
                .as("no length needed, then the larger precision, written without a length").isEqualTo("OTHERLONG");
    }

    @Test
    @DisplayName("RUN-002 a driver without a large object type: the VARCHAR type that holds the most characters with its length, if it holds at least 32768; the first name on a tie")
    void run002_theLongestVarchar() throws SQLException {
        DatabaseMetaData driver = driverWith(new TypeInfo("name", Types.VARCHAR, 63, "max length"), INTEGER,
                new TypeInfo("zlast", Types.VARCHAR, 2_147_483_647L, "(M)"), new TypeInfo("varchar", Types.VARCHAR, 10_485_760, "(M)"),
                new TypeInfo("afirst", Types.VARCHAR, 2_147_483_647L, "(M)"));

        assertThat(chosen(SchemaHistoryRepository.typeInfo(driver).toArray(TypeInfo[]::new))).isEqualTo("afirst(2147483647)");
        assertThat(LongTextType.choose(List.of(new TypeInfo("just", Types.VARCHAR, LongTextType.MINIMUM_LENGTH, "(M)"))))
                .as("exactly the least length is enough").isEqualTo("just(32768)");
    }

    @Test
    @DisplayName("RUN-003 a driver without a usable character type fails with SCHEMA_LONG_TEXT_TYPE_MISSING, whose English and Korean text name the missing type")
    void run003_noLongCharacterType() throws SQLException {
        List<DatabaseMetaData> drivers = List.of(driverWith(), driverWith(INTEGER, SHORT_VARCHAR),
                driverWith(new TypeInfo("almost", Types.VARCHAR, LongTextType.MINIMUM_LENGTH - 1L, null)),
                // a name that is not a plain type name is never put into the DDL, even for the right JDBC type
                driverWith(new TypeInfo("text; DROP TABLE x", Types.LONGVARCHAR, 0, null),
                        new TypeInfo("CLOB(1)", Types.CLOB, MAX, null), new TypeInfo(null, Types.VARCHAR, 100_000, null)));

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
    @DisplayName("RUN-001 the rows are read as the driver gives them: TYPE_NAME, DATA_TYPE, PRECISION and CREATE_PARAMS of every row of getTypeInfo")
    void run001_theRowsOfTheTypeInfo() throws SQLException {
        assertThat(SchemaHistoryRepository.typeInfo(driverWith(INTEGER, SHORT_VARCHAR)))
                .containsExactly(INTEGER, SHORT_VARCHAR);
    }

    @Test
    @DisplayName("RUN-020 CREATE_PARAMS does not leave a CLOB out: it is declared with the reported maximum length; a LONGVARCHAR without parameters goes before it")
    void run020_clobWithParameters() {
        TypeInfo clob = new TypeInfo("CLOB", Types.CLOB, MAX, "LENGTH");
        TypeInfo bigVarchar = new TypeInfo("VARCHAR", Types.VARCHAR, MAX, null);

        assertThat(chosen(bigVarchar, clob)).as("the large object type before the VARCHAR, the length written")
                .isEqualTo("CLOB(2147483647)");
        assertThat(chosen(clob, new TypeInfo("LONGTEXT", Types.LONGVARCHAR, 4_294_967_295L, null), bigVarchar))
                .isEqualTo("LONGTEXT");
    }

    @Test
    @DisplayName("RUN-021 the type without CREATE_PARAMS goes first (PostgreSQL text), a name of words is a candidate, a type that needs a length but reports none is not")
    void run021_theRankingDetails() {
        assertThat(chosen(new TypeInfo("name", Types.VARCHAR, 63, null), new TypeInfo("varchar", Types.VARCHAR, 10_485_760, "max length"),
                new TypeInfo("text", Types.VARCHAR, 10_485_760, null))).isEqualTo("text");
        assertThat(chosen(new TypeInfo("CHARACTER LARGE OBJECT", Types.CLOB, MAX, "LENGTH"),
                new TypeInfo("CHARACTER VARYING", Types.VARCHAR, MAX, "LENGTH"))).isEqualTo("CHARACTER LARGE OBJECT(2147483647)");
        assertThat(chosen(new TypeInfo("NOLEN", Types.CLOB, 0, "LENGTH"), new TypeInfo("varchar", Types.VARCHAR, 40_000, "(M)")))
                .as("a large object type that needs a length but reports none cannot be declared").isEqualTo("varchar(40000)");
    }
}
