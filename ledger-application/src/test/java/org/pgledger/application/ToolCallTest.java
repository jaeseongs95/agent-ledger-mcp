// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.application.gate.ToolCall;
import org.pgledger.domain.context.ToolScope;

/** SVC-GATE-42: the call that an attestation is checked against (no Spring, no database). */
class ToolCallTest {

    private static final String TOOL = "ledger_save";
    private static final String DIGEST = "a".repeat(64);
    private static final String OTHER_DIGEST = "b".repeat(64);

    @Test
    @DisplayName("SVC-GATE-42 (TXA-05): the call is read from the scope as one of three variants, never as null: outside a tool call, a tool without a digest, a tool with its digest; a digest without a tool is outside a tool call")
    void gate42_currentIsOneOfThreeVariants() {
        assertThat(ToolCall.current()).isEqualTo(new ToolCall.Outside());
        ScopedValue.where(ToolScope.CURRENT, TOOL)
                .run(() -> assertThat(ToolCall.current()).isEqualTo(new ToolCall.Named(TOOL)));
        ScopedValue.where(ToolScope.CURRENT, TOOL).where(ToolScope.ARGUMENTS_DIGEST, DIGEST)
                .run(() -> assertThat(ToolCall.current()).isEqualTo(new ToolCall.Digested(TOOL, DIGEST)));
        ScopedValue.where(ToolScope.ARGUMENTS_DIGEST, DIGEST)
                .run(() -> assertThat(ToolCall.current()).isEqualTo(new ToolCall.Outside()));
    }

    @Test
    @DisplayName("SVC-GATE-42 (TXA-05): what each variant matches of a signed tool and a signed digest: outside a tool call nothing, a tool without a digest only its tool, a tool with its digest exactly both")
    void gate42_matching() {
        ToolCall outside = new ToolCall.Outside();
        assertThat(outside.isTool(TOOL)).isFalse();
        assertThat(outside.carries(DIGEST)).isFalse();

        ToolCall named = new ToolCall.Named(TOOL);
        assertThat(named.isTool(TOOL)).isTrue();
        assertThat(named.isTool("ledger_transition")).isFalse();
        assertThat(named.carries(DIGEST)).isFalse();

        ToolCall digested = new ToolCall.Digested(TOOL, DIGEST);
        assertThat(digested.isTool(TOOL)).isTrue();
        assertThat(digested.isTool("ledger_transition")).isFalse();
        assertThat(digested.carries(DIGEST)).isTrue();
        assertThat(digested.carries(OTHER_DIGEST)).isFalse();
    }
}
