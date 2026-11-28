// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.write;

import org.veridger.application.write.WriteResult.SaveResult;
import org.veridger.domain.error.ValidationException;
import org.veridger.domain.error.ValidationReason;
import org.veridger.domain.rule.Channel;
import org.veridger.domain.value.OwnerIdentity;
import org.veridger.domain.value.TextRule;
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

    public SaveResult createForVersion(OwnerIdentity owner, SaveCommand command) {
        if (!command.isCreate()) {
            throw ValidationException.invalid("project_version", ValidationReason.EXISTS_ONLY_FOR, "create");
        }
        TextRule.PROJECT_VERSION_NAME.required(command.projectVersion());
        java.util.Objects.requireNonNull(owner);
        return operation.runAsOwner(command);
    }
}
