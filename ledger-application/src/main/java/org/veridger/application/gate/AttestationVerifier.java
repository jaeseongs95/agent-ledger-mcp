// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.gate;

import org.veridger.domain.error.TrustException;
import org.veridger.domain.value.AuthorId;

/** Turns what a caller presented as {@code _hostAttestation} into the author it proves, or refuses it. */
public interface AttestationVerifier extends McpCallerResolver {

    /**
     * Checks that {@code _hostAttestation} is present and has the ledger {@code <vendor>:<session-id>} format. A missing
     * or blank value throws {@code HOST_ATTESTATION_REQUIRED}; an invalid format returns {@link Proof.MalformedAuthor}
     * (answered as data by {@code ledger_whoami} and refused as {@code AUTHOR_ID_FORMAT} on writes).
     *
     * @param presented    the raw value of {@code _hostAttestation}; {@code null} or blank is {@code HOST_ATTESTATION_REQUIRED}
     * @param call         current tool call metadata
     * @param operationKey operation key of the call, or empty when none applies
     * @throws TrustException when {@code presented} is missing or blank
     */
    @Override
    Proof prove(String presented, ToolCall call, String operationKey);

    /** As {@link #prove}, with a malformed author refused as AUTHOR_ID_FORMAT: the author the attestation names. */
    @Override
    default AuthorId verify(String presented, ToolCall call, String operationKey) {
        return prove(presented, call, operationKey).requireAuthor();
    }

    /** As {@link #verify}, for a call that writes, inside the transaction of the write. */
    @Override
    AuthorId verifyForWrite(String presented, ToolCall call, String operationKey);
}
