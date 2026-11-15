package org.pgledger.application.write;

/**
 * Records one observation of a defect. {@code attachmentId} is the optional id of an attachment of the author that
 * the DEFECT_CASE row points to (checked in the transaction of the write).
 */
public record RecordCaseCommand(String operationKey, String defectId, String observation, String reason,
        String evidence, String userQuote, String observedAt, String attachmentId) implements WriteCommand {

    /** An observation without an attachment. */
    public RecordCaseCommand(String operationKey, String defectId, String observation, String reason, String evidence,
            String userQuote, String observedAt) {
        this(operationKey, defectId, observation, reason, evidence, userQuote, observedAt, null);
    }
}
