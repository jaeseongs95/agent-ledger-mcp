// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application.gate;

import org.pgledger.domain.error.TrustException;
import org.pgledger.domain.value.AuthorId;

/**
 * Vendor-neutral port that resolves the authenticated {@link AuthorId} of an MCP tool invocation.
 *
 * <p>Two runtime implementations back this contract:
 * <ul>
 *   <li>{@code STANDARD} mode: resolves the caller from an HTTP {@code Authorization: Bearer <session_token>}
 *       verified against {@code session_token}.</li>
 *   <li>{@code ATTESTED} mode: delegates to {@link AttestationVerifier} to check an Ed25519 signed host
 *       attestation bound to the tool name, operation key, and arguments digest.</li>
 * </ul>
 */
public interface McpCallerResolver {

    /**
     * Resolves the caller for a read or diagnostic check such as {@code ledger_whoami}.
     *
     * @param presented    presented caller proof (raw attestation in {@code ATTESTED} mode; optional in {@code STANDARD})
     * @param call         current tool call metadata
     * @param operationKey operation key bound to the call, or empty when none applies
     */
    AttestationVerifier.Proof prove(String presented, ToolCall call, String operationKey);

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

    /**
     * True when an authenticated per-author identity is bound to the current transport call (such as an HTTP
     * {@code Bearer} session token in {@code STANDARD} mode), even if no explicit {@code _hostAttestation} argument
     * was supplied.
     */
    default boolean hasAmbientCaller() {
        return false;
    }
}
