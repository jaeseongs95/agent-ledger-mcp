package org.pgledger.domain.value;

import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;

/**
 * The actor id of a row imported from another ledger that does not follow the author format; only an import and the
 * reads of imported rows produce it.
 */
public record ImportedActorId(String value) implements ActorId {

    public ImportedActorId {
        if (value == null || value.isBlank() || value.length() > 200) {
            throw ValidationException.invalid("actor_id", ValidationReason.IMPORTED_ACTOR_ID_LENGTH, "1", "200");
        }
    }
}
