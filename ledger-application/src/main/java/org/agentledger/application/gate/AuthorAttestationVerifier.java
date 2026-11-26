// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application.gate;

import java.util.List;
import org.agentledger.domain.error.TrustCode;
import org.agentledger.domain.error.TrustException;
import org.agentledger.domain.value.AuthorId;
import org.springframework.stereotype.Component;

/**
 * Resolves the caller identity from the host-stamped {@code _hostAttestation} value ({@code <vendor>:<session-id>}).
 *
 * <p>Transport perimeter authentication (HTTP {@code Authorization: Bearer <ledger.mcp.token>}, Host allowlist, Origin
 * allowlist, and request size limit) runs in {@code ledger-mcp-server} before any tool is reached. Once the transport
 * perimeter is authenticated, this verifier validates the caller author format and {@link WriteGate} enforces live
 * per-project, per-scope {@code session_grant} rows and reviewer-channel separation.
 */
@Component
public class AuthorAttestationVerifier implements AttestationVerifier {

    @Override
    public Proof prove(String presented, ToolCall call, String operationKey) {
        if (presented == null || presented.isBlank()) {
            throw new TrustException(TrustCode.HOST_ATTESTATION_REQUIRED, List.of());
        }
        if (!AuthorId.matches(presented)) {
            return new Proof.MalformedAuthor(presented);
        }
        return new Proof.Proven(AuthorId.of(presented));
    }

    @Override
    public AuthorId verifyForWrite(String presented, ToolCall call, String operationKey) {
        return prove(presented, call, operationKey).requireAuthor();
    }
}
