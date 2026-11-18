// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application.write;

/**
 * Records one observed occurrence under an issue. {@code attachmentId} is an optional attachment bundle owned by the
 * recording author.
 */
public record RecordCaseCommand(String operationKey, String issueId, String observation, String reason,
        String evidence, String sourceQuote, String observedAt, String attachmentId) implements WriteCommand {

    public RecordCaseCommand(String operationKey, String issueId, String observation, String reason, String evidence,
            String sourceQuote, String observedAt) {
        this(operationKey, issueId, observation, reason, evidence, sourceQuote, observedAt, null);
    }
}
