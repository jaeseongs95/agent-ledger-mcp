package org.pgledger.database.schema;

import java.sql.Types;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import org.pgledger.database.repository.SchemaHistoryRepository.TypeInfo;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;

/**
 * The type of a long text column, as the database reports it. The schema files are one text for every DBMS; the type of a long text
 * ({@code TEXT}, {@code CLOB}, {@code VARCHAR} without a length, ...) is the one place where DBMSs differ, so the files write the
 * placeholder {@value #PLACEHOLDER} and the migrator puts the declaration of the type in its place. The declaration is chosen here, in
 * this one place, from what the driver says in {@code DatabaseMetaData.getTypeInfo()} (read by {@code SchemaHistoryRepository.typeInfo}):
 * no setting, no list of DBMS names, no switch on the product.
 *
 * <p>The candidates are the types with a plain name whose {@code DATA_TYPE} is {@link Types#CLOB}, {@link Types#LONGVARCHAR} or
 * {@link Types#VARCHAR}. {@code CREATE_PARAMS} only tells which parameters can be used in creating the type, it does not say that one is
 * required (JDBC: "may be null"), so a large object type is not left out because it has parameters. The ranking: a large object type
 * ({@code CLOB}, {@code LONGVARCHAR}) before {@code VARCHAR}; within one group a type whose {@code CREATE_PARAMS} is empty (no length
 * needed) before one that has them; then the larger {@code PRECISION}; then the name, so the choice does not depend on the order of the
 * rows. A {@code VARCHAR} is taken only when it holds at least {@value #MINIMUM_LENGTH} characters, which is more than the longest text
 * of the ledger (a field is at most 20000 characters, see {@code TextRule}).
 *
 * <p>The declaration is the name alone when the {@code CREATE_PARAMS} of the chosen type is empty, and {@code name(PRECISION)}, the
 * greatest length the driver reports, when it is not: some DBMSs give a small default limit to a type declared without a length. A driver
 * whose type info has no candidate fails the migration ({@code SCHEMA_LONG_TEXT_TYPE_MISSING}).
 */
public final class LongTextType {

    /** What the schema files write where a long text type belongs. */
    public static final String PLACEHOLDER = "${long_text}";
    /** The least length (in characters) of a {@link Types#VARCHAR} type that is taken for a long text type. */
    public static final int MINIMUM_LENGTH = 32768;

    /**
     * The name is put into the text of the DDL, so only a plain type name is a candidate: words of letters, digits and underscores, with
     * single spaces between words ({@code CHARACTER LARGE OBJECT} is one type name).
     */
    private static final Pattern PLAIN_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,62}( [A-Za-z][A-Za-z0-9_]{0,62}){0,3}");

    private static final Comparator<TypeInfo> RANK = Comparator
            .comparing((TypeInfo type) -> type.dataType() == Types.VARCHAR)
            .thenComparing(TypeInfo::needsLength)
            .thenComparing(Comparator.comparingLong(TypeInfo::precision).reversed())
            .thenComparing(TypeInfo::name);

    private LongTextType() {
    }

    /**
     * The choice itself, over the rows of the type info.
     *
     * @return the declaration to put in place of the placeholder: the type name, with its length in parentheses when the type takes one
     * @throws InfrastructureException SCHEMA_LONG_TEXT_TYPE_MISSING when the driver reports no usable type
     */
    public static String choose(List<TypeInfo> types) {
        TypeInfo best = types.stream().filter(LongTextType::candidate).min(RANK).orElseThrow(
                () -> new InfrastructureException(InfrastructureCode.SCHEMA_LONG_TEXT_TYPE_MISSING,
                        List.of("LONGVARCHAR", String.valueOf(MINIMUM_LENGTH))));
        return best.needsLength() ? best.name() + "(" + best.precision() + ")" : best.name();
    }

    private static boolean candidate(TypeInfo type) {
        if (type.name() == null || !PLAIN_NAME.matcher(type.name()).matches()) {
            return false;
        }
        return switch (type.dataType()) {
            case Types.CLOB, Types.LONGVARCHAR -> !type.needsLength() || type.precision() > 0;
            case Types.VARCHAR -> type.precision() >= MINIMUM_LENGTH;
            default -> false;
        };
    }
}
