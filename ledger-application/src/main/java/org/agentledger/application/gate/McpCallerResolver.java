// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application.gate;

import java.util.List;
import org.agentledger.domain.error.TrustCode;
import org.agentledger.domain.error.TrustException;
import org.agentledger.domain.value.AuthorId;

/**
 * Vendor-neutral port that resolves the {@link AuthorId} of an MCP tool invocation from the host-stamped
 * {@code _hostAttestation} value after transport perimeter authentication.
 */
public interface McpCallerResolver {

    /**
     * What {@link #prove} found: the author the attestation names, or a malformed non-blank author (the one outcome
     * that {@code ledger_whoami} answers as data instead of as an error).
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

        /** The text that was presented as the author has no ledger format. */
        record MalformedAuthor(String text) implements Proof {

            @Override
            public AuthorId requireAuthor() {
                throw new TrustException(TrustCode.AUTHOR_ID_FORMAT, List.of());
            }
        }
    }

    /**
     * Resolves the caller for a read or diagnostic check such as {@code ledger_whoami}.
     *
     * @param presented    host-stamped {@code <vendor>:<session-id>} author identifier
     * @param call         current tool call metadata
     * @param operationKey operation key bound to the call, or empty when none applies
     */
    Proof prove(String presented, ToolCall call, String operationKey);

    /** Resolves and requires a valid {@link AuthorId} for a read path. */
    default AuthorId verify(String presented, ToolCall call, String operationKey) {
        return prove(presented, call, operationKey).requireAuthor();
    }

    /**
     * Resolves and requires a valid {@link AuthorId} inside the write transaction.
     *
     * @throws TrustException when caller authentication or binding fails
     */
    AuthorId verifyForWrite(String presented, ToolCall call, String operationKey);
}
