// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.domain.value;

import java.util.Optional;
import org.pgledger.domain.error.ValidationException;

/** Id of a milestone criterion: free text ({@link EntityId#PATTERN}, any case), supplied by the caller. */
public record CriterionId(String value) {

    public CriterionId {
        EntityId.requireValid("criterion_id", value);
    }

    /** The id of a text, or empty when the text is not a valid id; nothing is thrown. */
    public static Optional<CriterionId> parse(String value) {
        return EntityId.isValid(value) ? Optional.of(new CriterionId(value)) : Optional.empty();
    }

    /** The failure of a text that is not a criterion id. */
    public static ValidationException malformed() {
        return EntityId.malformed("criterion_id");
    }

    public static CriterionId of(String value) {
        return new CriterionId(value);
    }

    /** The key under the UNIQUE constraint of the table: upper-cased id ({@link EntityId#key}). */
    public String key() {
        return EntityId.key(value);
    }
}
