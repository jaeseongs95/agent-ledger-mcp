// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.agentledger.mcp.error.ToolError;
import org.agentledger.mcp.support.Args;
import org.agentledger.mcp.support.Ledger;
import org.agentledger.mcp.support.McpServerTest;
import org.agentledger.mcp.support.McpSession;
import org.agentledger.mcp.support.Scenario;
import org.agentledger.mcp.support.Seed;
import org.agentledger.mcp.support.WriteCase;
import org.agentledger.mcp.tool.WriteOutputs;

/**
 * MCW-030 .. MCW-039 (v4.0.0 direct host attestation): the server validates {@code _hostAttestation} as a strict
 * {@code <vendor>:<session_id>} author identifier stamped by the host hook and enforces {@code session_grant}
 * permissions without per-session Ed25519 keys or per-session Bearer tokens.
 */
@McpServerTest
class AttestationWireTest {

    private static final String WHOAMI = "ledger_whoami";

    private static Args argsOf(McpSession session, WriteCase write, Seed seed, String operationKey) {
        Args call = Args.from(session.mapper(), write.call(seed));
        if (!WHOAMI.equals(write.tool())) {
            call = call.with("operationKey", operationKey);
        }
        return call;
    }

    private static Args callOf(McpSession session, WriteCase write, Seed seed, String operationKey, String attestation) {
        Args base = argsOf(session, write, seed, operationKey);
        return attestation == null ? base.without("_hostAttestation") : base.with("_hostAttestation", attestation);
    }

    private static String operationKeyFor(WriteCase write) {
        return WHOAMI.equals(write.tool()) ? "" : UUID.randomUUID().toString();
    }

    private static ToolError refused(McpSession session, WriteCase write, Args call, String code, String... mustNotShow) {
        CallToolResult result = session.callUnsigned(write.tool(), call);
        assertThat(result.isError()).as("%s must be refused with %s", write.tool(), code).isTrue();
        ToolError error = session.error(result);
        assertThat(error.code()).isEqualTo(code);
        for (String secret : mustNotShow) {
            assertThat(error.message()).doesNotContain(secret);
            assertThat(error.details().toString()).doesNotContain(secret);
        }
        return error;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.agentledger.mcp.support.WriteCase#all")
    @DisplayName("MCW-030: missing or blank _hostAttestation is HOST_ATTESTATION_REQUIRED for all 13 write tools and writes nothing")
    void missingOrBlankAttestationIsRefused(WriteCase write, McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);
        int before = Ledger.history(session, seed.project()).size();

        refused(session, write, callOf(session, write, seed, operationKeyFor(write), null),
                "HOST_ATTESTATION_REQUIRED");
        refused(session, write, callOf(session, write, seed, operationKeyFor(write), "   "),
                "HOST_ATTESTATION_REQUIRED");

        assertThat(Ledger.history(session, seed.project())).hasSize(before);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.agentledger.mcp.support.WriteCase#all")
    @DisplayName("MCW-031: a granted plain <vendor>:<session_id> succeeds directly for all 13 write tools without session-key registration")
    void grantedPlainAuthorSucceedsDirectly(WriteCase write, McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);
        Args call = callOf(session, write, seed, operationKeyFor(write), write.actor());

        CallToolResult result = session.callUnsigned(write.tool(), call);

        assertThat(result.isError()).as("%s must succeed: %s", write.tool(), result.content()).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.agentledger.mcp.support.WriteCase#keyed")
    @DisplayName("MCW-032: an ungranted plain <vendor>:<session_id> is SESSION_NOT_GRANTED for all 12 keyed write tools and writes nothing")
    void ungrantedAuthorIsRefused(WriteCase write, McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);
        int before = Ledger.history(session, seed.project()).size();

        refused(session, write, callOf(session, write, seed, operationKeyFor(write), Scenario.NOGRANT),
                "SESSION_NOT_GRANTED");

        assertThat(Ledger.history(session, seed.project())).hasSize(before);
    }

    @Test
    @DisplayName("MCW-037: replaying a write with the same operationKey returns the stored response; changing payload under the same operationKey is OPERATION_CONFLICT")
    void idempotentReplayAndOperationConflict(McpSession session, Scenario scenario) {
        String author = scenario.actorWith("rp", "issue");
        String operationKey = UUID.randomUUID().toString();
        Args call = Args.of().with("project_id", scenario.projectId()).with("title", "captured")
                .with("category", "ARC").with("severity", "P2").with("symptom", "symptom of captured")
                .with("root_cause", "cause of captured").with("reason", Scenario.REASON)
                .with("operationKey", operationKey).with("_hostAttestation", author);

        WriteOutputs.Saved first = session.ok("ledger_issue_save", call, WriteOutputs.Saved.class);
        WriteOutputs.Saved again = session.ok("ledger_issue_save", call, WriteOutputs.Saved.class);
        assertThat(again).isEqualTo(first);

        ToolError conflict = session.failed("ledger_issue_save", call.with("title", "different content"));
        assertThat(conflict.code()).isEqualTo("OPERATION_CONFLICT");
        assertThat(Ledger.history(session, scenario.projectId())).hasSize(1);
    }

    @Test
    @DisplayName("MCW-038: whoami lists grants for a valid author, empty grants for an ungranted author, and author_format_ok=false for malformed text")
    void whoami(McpSession session, Scenario scenario) {
        String author = scenario.actorWith("who", "issue", "task");
        WriteOutputs.Whoami who = session.ok(WHOAMI, Args.of().with("_hostAttestation", author),
                WriteOutputs.Whoami.class);
        assertThat(who.author()).isEqualTo(author);
        assertThat(who.authorFormatOk()).isTrue();
        assertThat(who.grants()).hasSize(2);

        WriteOutputs.Whoami ungranted = session.ok(WHOAMI, Args.of().with("_hostAttestation", Scenario.NOGRANT),
                WriteOutputs.Whoami.class);
        assertThat(ungranted.author()).isEqualTo(Scenario.NOGRANT);
        assertThat(ungranted.authorFormatOk()).isTrue();
        assertThat(ungranted.grants()).isEmpty();

        assertThat(session.ok(WHOAMI, Args.of().with("_hostAttestation", "claude"), WriteOutputs.Whoami.class)
                .authorFormatOk()).isFalse();
    }

    @Test
    @DisplayName("MCC-028: no tool registers, revokes or lists keys, tokens or grants: the owner does that in the admin web and the CLI only")
    void noKeyOrGrantTool(McpSession session) {
        assertThat(session.tools()).extracting(io.modelcontextprotocol.spec.McpSchema.Tool::name)
                .noneMatch(name -> name.contains("key") || name.contains("grant") || name.contains("register")
                        || name.contains("revoke") || name.contains("attest") || name.contains("token"));
    }

    @Test
    @DisplayName("MCW-039: malformed values (including retired ala3.* and pgla1.* token formats) are AUTHOR_ID_FORMAT and never echoed")
    void malformedAuthorAndRetiredTokensAreRefused(McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);
        WriteCase save = WriteCase.all().getFirst();
        for (String text : List.of("claude", "no-colon", "ala3.", "ala3.a.b.c.d.e", "ala3." + "A".repeat(200),
                "pgla1.", "pgla1.a.b.c.1.AAAA")) {
            refused(session, save, callOf(session, save, seed, UUID.randomUUID().toString(), text),
                    "AUTHOR_ID_FORMAT", text);
        }
    }
}
