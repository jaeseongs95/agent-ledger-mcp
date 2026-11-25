// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application.write;

/**
 * Records a deliverable produced for an issue, specification, or task, or amends check evidence on an existing
 * deliverable. {@code attachmentId} is an optional attachment bundle owned by the recording author.
 */
public record RecordDeliverableCommand(String operationKey, String targetEntity, String targetId, String kind,
        String deliverableRef, String reason, String location, String summary, String checkRef, String checkResult,
        String checkedAt, Boolean markDone, String attachmentId) implements WriteCommand {

    public RecordDeliverableCommand(String operationKey, String targetEntity, String targetId, String kind,
            String deliverableRef, String reason, String location, String summary, String checkRef,
            String checkResult, String checkedAt, Boolean markDone) {
        this(operationKey, targetEntity, targetId, kind, deliverableRef, reason, location, summary, checkRef,
                checkResult, checkedAt, markDone, null);
    }
}
