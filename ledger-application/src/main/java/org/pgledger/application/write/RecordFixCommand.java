package org.pgledger.application.write;

/**
 * Records a fix commit of a defect, or new CI data of a known commit. {@code attachmentId} is the optional id of an
 * attachment of the author that the DEFECT_FIX row points to (checked in the transaction of the write).
 */
public record RecordFixCommand(String requestId, String defectId, String repo, String commitSha, String reason,
        String branch, String ciRef, String ciConclusion, String ciCompletedAt, Boolean markSourceFixed,
        String attachmentId) implements WriteCommand {

    /** A fix without an attachment. */
    public RecordFixCommand(String requestId, String defectId, String repo, String commitSha, String reason,
            String branch, String ciRef, String ciConclusion, String ciCompletedAt, Boolean markSourceFixed) {
        this(requestId, defectId, repo, commitSha, reason, branch, ciRef, ciConclusion, ciCompletedAt,
                markSourceFixed, null);
    }
}
