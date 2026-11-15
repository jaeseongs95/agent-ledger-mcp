package org.pgledger.database.schema;

import java.sql.Types;
import java.util.List;
import java.util.regex.Pattern;
import org.pgledger.database.repository.SchemaHistoryRepository.TypeInfo;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;

/**
 * The type of a long text column, as the database reports it. The schema files are one text for every DBMS; the type of a long text
 * ({@code TEXT}, {@code CLOB}, {@code VARCHAR} without a length, ...) is the one place where DBMSs differ, so the files write the
 * placeholder {@value #PLACEHOLDER} and the migrator puts the type in its place. The type is chosen here, in this one place, from what
 * the driver says in {@code DatabaseMetaData.getTypeInfo()} (read by {@code SchemaHistoryRepository.typeInfo}): no setting, no
 * list of DBMS names, no switch on the product.
 *
 * <p>The choice: a type whose {@code DATA_TYPE} is {@link Types#LONGVARCHAR} (the JDBC long character type) is the long text type. A
 * driver that reports none (PostgreSQL's driver reports {@code text} as {@link Types#VARCHAR}) offers its character types of
 * {@link Types#VARCHAR}, and the one with the largest {@code PRECISION} (the longest text it holds) is chosen when it holds at least
 * {@value #MINIMUM_LENGTH} characters, which is more than the longest text of the ledger (a field is at most 20000 characters, see
 * {@code TextRule}). A driver whose type info has neither fails the migration ({@code SCHEMA_LONG_TEXT_TYPE_MISSING}).
 */
public final class LongTextType {

    /** What the schema files write where a long text type belongs. */
    public static final String PLACEHOLDER = "${long_text}";
    /** The least length (in characters) of a {@link Types#VARCHAR} type that is taken for a long text type. */
    public static final int MINIMUM_LENGTH = 32768;

    /** The name is put into the text of the DDL, so only a plain type name is a candidate. */
    private static final Pattern PLAIN_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,62}");

    private LongTextType() {
    }

    /**
     * The choice itself, over the rows of the type info in the order the driver gave them.
     *
     * @throws InfrastructureException SCHEMA_LONG_TEXT_TYPE_MISSING when the driver reports no long character type
     */
    public static String choose(List<TypeInfo> types) {
        for (TypeInfo type : types) {
            if (type.dataType() == Types.LONGVARCHAR && plain(type)) {
                return type.name();
            }
        }
        TypeInfo longest = null;
        for (TypeInfo type : types) {
            if (type.dataType() == Types.VARCHAR && plain(type) && type.precision() >= MINIMUM_LENGTH
                    && (longest == null || type.precision() > longest.precision())) {
                longest = type;
            }
        }
        if (longest == null) {
            throw new InfrastructureException(InfrastructureCode.SCHEMA_LONG_TEXT_TYPE_MISSING,
                    List.of("LONGVARCHAR", String.valueOf(MINIMUM_LENGTH)));
        }
        return longest.name();
    }

    private static boolean plain(TypeInfo type) {
        return type.name() != null && PLAIN_NAME.matcher(type.name()).matches();
    }
}
