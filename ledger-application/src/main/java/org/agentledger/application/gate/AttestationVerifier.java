// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application.gate;

import java.util.List;
import org.agentledger.domain.error.TrustCode;
import org.agentledger.domain.error.TrustException;
import org.agentledger.domain.value.AuthorId;

/** Turns what a caller presented as {@code _hostAttestation} into the author it proves, or refuses it. */
public interface AttestationVerifier extends McpCallerResolver {

    /**
     * What {@link #prove} found: the author the attestation proves, or an author without the ledger format (the one
     * outcome that the whoami tool answers as data instead of as an error).
     */
    sealed interface Proof {

        /**
         * The proven author.
         *
         * @throws TrustException AUTHOR_ID_FORMAT when the author has no ledger format
         */
        AuthorId requireAuthor();

        /** The attestation is valid for this author. */
        record Proven(AuthorId author) implements Proof {

            @Override
            public AuthorId requireAuthor() {
                return author;
            }
        }

        /** The text that was presented as the author (or as the whole attestation) has no author format. */
        record MalformedAuthor(String text) implements Proof {

            @Override
            public AuthorId requireAuthor() {
                throw new TrustException(TrustCode.AUTHOR_ID_FORMAT, List.of());
            }
        }
    }

    /**
     * Checks that {@code _hostAttestation} is present and has the ledger {@code <vendor>:<session-id>} format. A missing
     * value throws {@code HOST_ATTESTATION_REQUIRED}; an invalid format returns {@link Proof.MalformedAuthor} (answered as
     * data by {@code ledger_whoami} and refused as {@code AUTHOR_ID_FORMAT} on writes).
     *
     * @param presented    the raw value of {@code _hostAttestation}; {@code null} is {@code HOST_ATTESTATION_REQUIRED}
     * @param call         current tool call metadata
     * @param operationKey operation key of the call, or empty when none applies
     * @throws TrustException when {@code presented} is missing
     */
    Proof prove(String presented, ToolCall call, String operationKey);

    /** As {@link #prove}, with a malformed author refused as AUTHOR_ID_FORMAT: the author the attestation names. */
    default AuthorId verify(String presented, ToolCall call, String operationKey) {
        return prove(presented, call, operationKey).requireAuthor();
    }

    /** As {@link #verify}, for a call that writes, inside the transaction of the write. */
    AuthorId verifyForWrite(String presented, ToolCall call, String operationKey);
}
