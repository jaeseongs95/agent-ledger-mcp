// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application.write;

import org.pgledger.application.gate.GateSession;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.Reason;
import org.pgledger.domain.value.OperationKey;

/** What the body of a write knows: the trusted session, the validated reason and the operation key. */
public record WriteContext(GateSession session, Reason reason, OperationKey operationKey) {

    public AuthorId author() {
        return session.author();
    }

    public String login() {
        return session.channel().login();
    }
}
