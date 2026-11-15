package org.pgledger.application.write;

/**
 * Moves a defect, task or condition to another state. {@code attachmentId} is the optional id of an attachment of the
 * author that the TRANSITION row points to (checked in the transaction of the write).
 */
public record TransitionCommand(String operationKey, String entity, String id, Long expectedVersion, String to,
        String reason, String evidenceUri, String evidenceSha256, String mergedInto, String attachmentId)
        implements WriteCommand {

    /** A move without an attachment. */
    public TransitionCommand(String operationKey, String entity, String id, Long expectedVersion, String to,
            String reason, String evidenceUri, String evidenceSha256, String mergedInto) {
        this(operationKey, entity, id, expectedVersion, to, reason, evidenceUri, evidenceSha256, mergedInto, null);
    }
}
