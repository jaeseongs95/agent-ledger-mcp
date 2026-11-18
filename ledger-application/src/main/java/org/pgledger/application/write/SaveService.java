// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application.write;

import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.domain.rule.Channel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Create or edit an issue, specification, task, or milestone criterion through the author channel. */
@Service
@Transactional
public class SaveService {

    private final SaveOperation operation;

    public SaveService(SaveOperation operation) {
        this.operation = operation;
    }

    public SaveResult save(String actor, SaveCommand command) {
        return operation.run(actor, Channel.AUTHOR, command);
    }
}
