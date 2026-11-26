// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application.write;

import org.agentledger.domain.error.ValidationException;
import org.agentledger.domain.error.ValidationReason;

/**
 * Points at a wrong history row with a CORRECT row. {@code attachmentId} is the optional id of an attachment of the
 * author that the CORRECT row points to (checked in the transaction of the write).
 */
public record CorrectCommand(String operationKey, Long historyId, String reason, String attachmentId)
        implements WriteCommand {

    /** A correction without an attachment. */
    public CorrectCommand(String operationKey, Long historyId, String reason) {
        this(operationKey, historyId, reason, null);
    }

    /**
     * The id of the history row to correct.
     *
     * @throws ValidationException when it is missing
     */
    public long requireHistoryId() {
        if (historyId == null) {
            throw ValidationException.invalid("history_id", ValidationReason.REQUIRED);
        }
        return historyId;
    }
}
