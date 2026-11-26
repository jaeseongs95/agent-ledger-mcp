// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application.gate;

import java.util.Objects;
import java.util.Optional;
import org.agentledger.attestation.AttestationClaims;
import org.agentledger.domain.context.ToolScope;

/**
 * The call that an attestation is checked against, as the tool binding of the MCP server bound it ({@link ToolScope}).
 * An attestation is signed for one tool and one digest of its arguments; what the call is decides whether they match.
 * The three situations that differ in shape are three variants, so that no value stands for "none" (no {@code null}, no
 * empty {@link java.util.Optional} that is handed on as a parameter).
 */
public sealed interface ToolCall {

    /** The call of the scope that is bound now: outside a tool call, a tool without a digest, or a tool with its digest. */
    static ToolCall current() {
        return ToolScope.current()
                .map(tool -> ToolScope.argumentsDigest()
                        .<ToolCall>map(digest -> new Digested(tool, digest))
                        .orElseGet(() -> new Named(tool)))
                .orElseGet(Outside::new);
    }

    /** True when the call is for this tool (the tool an attestation was signed for). */
    boolean isTool(String signedTool);

    /** True when the call carries exactly this digest of arguments (the digest an attestation was signed for). */
    boolean carries(String signedDigest);

    /** First bound claim field that disagrees with this call, delegated to {@link AttestationClaims#firstMismatch}. */
    Optional<AttestationClaims.Field> firstMismatch(AttestationClaims claims, String operationKey);

    /**
     * Not inside a tool call (a service that is called directly): no tool and no digest are known, so no signed
     * attestation matches.
     */
    record Outside() implements ToolCall {

        @Override
        public boolean isTool(String signedTool) {
            return false;
        }

        @Override
        public boolean carries(String signedDigest) {
            return false;
        }

        @Override
        public Optional<AttestationClaims.Field> firstMismatch(AttestationClaims claims, String operationKey) {
            return claims.firstMismatch(null, operationKey, null);
        }
    }

    /** Inside a tool call that carries no digest (a call without attestation binds none): only the tool can match. */
    record Named(String tool) implements ToolCall {

        public Named {
            Objects.requireNonNull(tool, "tool");
        }

        @Override
        public boolean isTool(String signedTool) {
            return Objects.equals(tool, signedTool);
        }

        @Override
        public boolean carries(String signedDigest) {
            return false;
        }

        @Override
        public Optional<AttestationClaims.Field> firstMismatch(AttestationClaims claims, String operationKey) {
            return claims.firstMismatch(tool, operationKey, null);
        }
    }

    /** Inside a tool call: the wire name of the tool and the digest of the arguments as the server received them. */
    record Digested(String tool, String argumentsDigest) implements ToolCall {

        public Digested {
            Objects.requireNonNull(tool, "tool");
            Objects.requireNonNull(argumentsDigest, "argumentsDigest");
        }

        @Override
        public boolean isTool(String signedTool) {
            return Objects.equals(tool, signedTool);
        }

        @Override
        public boolean carries(String signedDigest) {
            return Objects.equals(argumentsDigest, signedDigest);
        }

        @Override
        public Optional<AttestationClaims.Field> firstMismatch(AttestationClaims claims, String operationKey) {
            return claims.firstMismatch(tool, operationKey, argumentsDigest);
        }
    }
}
