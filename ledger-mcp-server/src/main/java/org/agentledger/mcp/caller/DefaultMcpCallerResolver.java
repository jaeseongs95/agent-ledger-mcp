// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.mcp.caller;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.agentledger.application.gate.AttestationVerifier;
import org.agentledger.application.gate.McpCallerResolver;
import org.agentledger.application.gate.ToolCall;
import org.agentledger.domain.error.TrustCode;
import org.agentledger.domain.error.TrustException;
import org.agentledger.domain.value.AuthorId;
import org.agentledger.mcp.config.AuthMode;
import org.agentledger.mcp.config.LedgerMcpProperties;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Resolves the authenticated {@link AuthorId} of an MCP call according to {@link LedgerMcpProperties#authMode()}.
 *
 * <p>When {@code _hostAttestation} is supplied, it is always verified through {@link AttestationVerifier} (and if a
 * per-author {@code Bearer} session token is also active on the request, both must name the same author). When
 * {@code _hostAttestation} is absent and {@link AuthMode#STANDARD} is enabled, a verified per-author {@code Bearer}
 * session token on the active HTTP request resolves the caller directly without any client hook.
 */
@Component
@Primary
public class DefaultMcpCallerResolver implements McpCallerResolver {

    private final AttestationVerifier attestationVerifier;
    private final LedgerMcpProperties properties;

    public DefaultMcpCallerResolver(AttestationVerifier attestationVerifier, LedgerMcpProperties properties) {
        this.attestationVerifier = attestationVerifier;
        this.properties = properties;
    }

    @Override
    public boolean hasAmbientCaller() {
        return properties.authMode() == AuthMode.STANDARD && ambientAuthor() != null;
    }

    @Override
    public AttestationVerifier.Proof prove(String presented, ToolCall call, String operationKey) {
        if (presented != null) {
            AttestationVerifier.Proof proof = attestationVerifier.prove(presented, call, operationKey);
            if (proof instanceof AttestationVerifier.Proof.Proven proven) {
                AuthorId ambient = ambientAuthor();
                if (ambient != null && !Objects.equals(ambient, proven.author())) {
                    throw new TrustException(TrustCode.ATTESTATION_BINDING_MISMATCH, List.of("author"),
                            Map.of("field", List.of("author")));
                }
            }
            return proof;
        }
        if (properties.authMode() == AuthMode.STANDARD) {
            AuthorId ambient = ambientAuthor();
            if (ambient != null) {
                return new AttestationVerifier.Proof.Proven(ambient);
            }
        }
        return attestationVerifier.prove(null, call, operationKey);
    }

    @Override
    public AuthorId verifyForWrite(String presented, ToolCall call, String operationKey) {
        return prove(presented, call, operationKey).requireAuthor();
    }

    private static AuthorId ambientAuthor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof AuthorId author ? author : null;
    }
}
