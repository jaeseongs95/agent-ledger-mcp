// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application.write;

import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;

/**
 * Create (expected version absent or 0) or edit (expected version of the row) an issue, specification, task, or
 * milestone criterion.
 */
public record SaveCommand(String operationKey, String entity, String id, Long expectedVersion,
        FieldsInput fields, String reason) implements WriteCommand {

    public boolean isCreate() {
        if (expectedVersion != null && expectedVersion < 0) {
            throw ValidationException.invalid("expected_version", ValidationReason.MUST_NOT_BE_NEGATIVE);
        }
        return expectedVersion == null || expectedVersion == 0;
    }

    public String requireId() {
        if (id == null || id.isBlank()) {
            throw ValidationException.invalid("id", ValidationReason.REQUIRED_TO_EDIT);
        }
        return id;
    }

    String mentionedProject() {
        return fields == null ? null : fields.projectId().orNull();
    }
}
