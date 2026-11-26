// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.cli.database;

import java.io.IOException;
import java.nio.file.FileSystems;
import org.agentledger.cli.ErrorCode;
import org.agentledger.cli.FailureException;
import org.agentledger.cli.secret.ProcessUser;
import org.agentledger.domain.value.OwnerIdentity;
import org.springframework.stereotype.Component;

/**
 * Who an owner operation of the CLI is recorded as in the owner's audit trail: {@code cli:<name of the process user>}
 * (the admin web records the identity the owner signed in with instead). The one place that builds it.
 */
@Component
public class CliActor {

    /** The audit actor prefix of the CLI. */
    public static final String PREFIX = "cli:";

    private final ProcessUser processUser;

    CliActor(ProcessUser processUser) {
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
