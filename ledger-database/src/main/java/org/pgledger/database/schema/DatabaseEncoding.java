package org.pgledger.database.schema;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;

/**
 * The encoding of the database has to be UTF-8: the schema files and the texts of the ledger are Unicode, and the digest of a file
 * is of its UTF-8 bytes. The names are what the database reports in the standard view {@code INFORMATION_SCHEMA.CHARACTER_SETS}
 * (read by {@code SchemaHistoryRepository.characterSets}; JDBC has no call for the encoding, and no product query is asked). A name
 * counts as UTF-8 when it is {@code UTF8} in any spelling of case and separators ({@code UTF-8}, {@code utf_8}).
 */
public final class DatabaseEncoding {

    private DatabaseEncoding() {
    }

    /**
     * @param characterSets the names the database reports
     * @throws InfrastructureException SCHEMA_ENCODING_NOT_UTF8 when there is none, or one of them is not UTF-8; the argument names
     *         what the database reports
     */
    public static void requireUtf8(List<String> characterSets) {
        boolean utf8 = !characterSets.isEmpty() && characterSets.stream().allMatch(DatabaseEncoding::isUtf8);
        if (!utf8) {
            throw new InfrastructureException(InfrastructureCode.SCHEMA_ENCODING_NOT_UTF8,
                    List.of(characterSets.isEmpty() ? "none" : String.join(", ", characterSets.stream()
                            .map(name -> Objects.toString(name, "null")).toList())));
        }
    }

    private static boolean isUtf8(String name) {
        return name != null && "UTF8".equals(name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", ""));
    }
}
