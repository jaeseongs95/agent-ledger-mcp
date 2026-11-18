package org.pgledger.application.write;

import org.pgledger.application.write.WriteResult.TransitionResult;
import org.pgledger.domain.rule.Channel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Change the state of an issue, specification, task, or milestone criterion through the author channel. */
@Service
@Transactional
public class TransitionService {

    private final TransitionOperation operation;

    public TransitionService(TransitionOperation operation) {
        this.operation = operation;
    }

    public TransitionResult transition(String actor, TransitionCommand command) {
        return operation.run(actor, Channel.AUTHOR, command);
    }
}
