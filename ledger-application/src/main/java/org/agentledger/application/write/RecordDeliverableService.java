// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application.write;

import org.agentledger.application.write.WriteResult.RecordDeliverableResult;
import org.agentledger.domain.rule.Channel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Record a deliverable on an issue, specification, or task through the author channel. */
@Service
@Transactional
public class RecordDeliverableService {

    private final RecordDeliverableOperation operation;

    public RecordDeliverableService(RecordDeliverableOperation operation) {
        this.operation = operation;
    }

    public RecordDeliverableResult recordDeliverable(String actor, RecordDeliverableCommand command) {
        return operation.run(actor, Channel.AUTHOR, command);
    }
}
