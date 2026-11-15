package org.pgledger.migrate1x.wiring;

import java.io.IOException;
import java.nio.file.FileSystems;
import org.pgledger.domain.value.OwnerIdentity;
import org.pgledger.migrate1x.secret.ProcessUser;
import org.pgledger.migrate1x.tool.ErrorCode;
import org.pgledger.migrate1x.tool.FailureException;
import org.springframework.stereotype.Component;

/**
 * Who an owner operation of the tool is recorded as in the owner's audit trail: {@code cli:<name of the process user>}, the
 * same actor the command line tool of 2.0 records (the one place that builds it).
 */
@Component
public class Actor {

    /** The audit actor prefix of the command line tools. */
    public static final String PREFIX = "cli:";

    private final ProcessUser processUser;

    Actor(ProcessUser processUser) {
        this.processUser = processUser;
    }

    /** @throws FailureException UNKNOWN_PROCESS_USER when the account of the process cannot be determined */
    public OwnerIdentity current() {
        try {
            return OwnerIdentity.of(PREFIX + processUser.current(FileSystems.getDefault()).getName());
        } catch (IOException failure) {
            throw new FailureException(ErrorCode.UNKNOWN_PROCESS_USER, failure);
        }
    }
}
