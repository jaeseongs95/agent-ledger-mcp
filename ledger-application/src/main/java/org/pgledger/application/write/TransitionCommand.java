// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application.write;

/**
 * Moves an issue, specification, task, or milestone criterion to another state.
 */
public record TransitionCommand(String operationKey, String entity, String id, Long expectedVersion, String to,
        String reason, String evidenceUri, String evidenceSha256, String mergedInto, String attachmentId)
        implements WriteCommand {

    public TransitionCommand(String operationKey, String entity, String id, Long expectedVersion, String to,
            String reason, String evidenceUri, String evidenceSha256, String mergedInto) {
        this(operationKey, entity, id, expectedVersion, to, reason, evidenceUri, evidenceSha256, mergedInto, null);
    }
}
