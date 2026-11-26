// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import org.agentledger.testing.TestOwners;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.agentledger.application.admin.SessionKeyService;
import org.agentledger.attestation.PublicKeys;
import org.agentledger.mcp.support.Args;
import org.agentledger.mcp.support.Calls;
import org.agentledger.mcp.support.HookProcess;
import org.agentledger.mcp.support.Ledger;
import org.agentledger.mcp.support.McpServerTest;
import org.agentledger.mcp.support.McpSession;
import org.agentledger.mcp.support.Scenario;
import org.agentledger.mcp.tool.WriteOutputs;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * MCW-040: the whole way of an attestation with the real parts. The built hook jar creates the key of a session and
 * prints it (as the session shows it to the owner), the owner registers it and grants the session, the hook jar signs the
 * write call of the session as Claude Code would start it, and the MCP Java SDK client sends exactly what the hook
 * answered. Nothing in the test signs anything.
 */
@McpServerTest
class HookRoundTripTest {

    private static final TypeReference<LinkedHashMap<String, Object>> OBJECT = new TypeReference<>() {
    };

    /** The work directory and key folder of the test: the resident program that the hook calls started is ended after it. */
    private Path residentWork;
    private Path residentKeys;

    @AfterEach
    void endTheResidentProgram() throws IOException {
        if (residentWork != null) {
            HookProcess.stop(residentWork, residentKeys);
        }
    }

    private static Args hooked(JsonMapper mapper, Path work, Path keys, String tool, String hostId, Object input)
            throws IOException {
        String event = mapper.writeValueAsString(Map.of("tool_name", "mcp__ledger__" + tool, "tool_input", input,
                "session_id", hostId));
        HookProcess.Result run = HookProcess.run(work, keys, event, "claude");
        assertThat(run.exit()).as(run.err()).isZero();
        assertThat(run.err()).isEmpty();
        JsonNode updated = mapper.readTree(run.out()).path("hookSpecificOutput").path("updatedInput");
        assertThat(updated.isObject()).as("the hook answered %s", run.out()).isTrue();
        return new Args(mapper.convertValue(updated, OBJECT));
    }

    @Test
    @DisplayName("MCW-040: key made by the hook, registered by the owner, write and whoami signed by the hook jar and sent by the MCP client succeed as that author; a changed operationKey or changed content under the signed key is refused (the hook makes no key up: the operationKey is the one the model sent)")
    void roundTrip(McpSession session, Scenario scenario, @Autowired SessionKeyService owner,
            @Autowired JsonMapper mapper, @TempDir Path work) throws IOException {
        Path keys = work.resolve("keys");
        residentWork = work;
        residentKeys = keys;
        String hostId = "rt" + UUID.randomUUID().toString().substring(0, 8);
        String author = "claude:" + hostId;

        // 1. the session asks the hook for its key and shows the fingerprint to the owner
        HookProcess.Result printed = HookProcess.run(work, keys, "", "session-key", "ledger", author);
        assertThat(printed.exit()).as(printed.err()).isZero();
        List<String> lines = printed.out().lines().toList();
        assertThat(lines).hasSize(4);
        assertThat(lines.get(0)).isEqualTo("server: ledger");
        assertThat(lines.get(1)).isEqualTo("author: " + author);
        String publicKey = lines.get(2).substring("public-key: ".length());
        String fingerprint = lines.get(3).substring("fingerprint: ".length());
        assertThat(PublicKeys.fingerprint(PublicKeys.decode(publicKey))).isEqualTo(fingerprint);

        // 2. the owner compares the fingerprint, registers the key and grants the session
        assertThat(owner.register(new SessionKeyService.RegisterCommand(author, publicKey, "round trip"), TestOwners.OWNER)
                .fingerprint()).isEqualTo(fingerprint);
        scenario.grant(author, "issue");

        // 3. a write call as the model made it (an operationKey of the model's own, no attestation), signed by the hook:
        // the hook adds the attestation and nothing else (it makes no key up and changes no other argument)
        Calls.IssueSave call = Calls.IssueSave.create(scenario.projectId(), "round trip", null);
        Args signed = hooked(mapper, work, keys, "ledger_issue_save", hostId, call);
        assertThat(signed.values()).containsKeys("_hostAttestation", "operationKey");
        assertThat(signed.values().get("operationKey")).isEqualTo(call.operationKey());
        assertThat(signed.without("_hostAttestation").values())
                .isEqualTo(Args.from(mapper, call).values());
        assertThat(String.valueOf(signed.values().get("_hostAttestation"))).startsWith("ala3.");

        WriteOutputs.Saved saved = session.ok("ledger_issue_save", signed, WriteOutputs.Saved.class);
        assertThat(saved.author()).isEqualTo(author);
        assertThat(saved.version()).isEqualTo(1);
        assertThat(Ledger.history(session, scenario.projectId())).hasSize(1);

        // 4. whoami, signed by the hook with the empty operation key (it carries none)
        Args whoami = hooked(mapper, work, keys, "ledger_whoami", hostId, Map.of());
        assertThat(whoami.values()).containsOnlyKeys("_hostAttestation");
        WriteOutputs.Whoami who = session.ok("ledger_whoami", whoami, WriteOutputs.Whoami.class);
        assertThat(who.author()).isEqualTo(author);
        assertThat(who.grants()).hasSize(1);

        // 5. what a bearer-token holder can do with a captured call: the same call is idempotent, nothing else works
        assertThat(session.ok("ledger_issue_save", signed, WriteOutputs.Saved.class)).isEqualTo(saved);
        assertThat(session.failed("ledger_issue_save", signed.with("operationKey", UUID.randomUUID().toString())).code())
                .isEqualTo("ATTESTATION_BINDING_MISMATCH");
        assertThat(session.failed("ledger_issue_save", signed.with("title", "other content")).code())
                .isEqualTo("ATTESTATION_BINDING_MISMATCH");
        CallToolResult asOther = session.callUnsigned("ledger_issue_save",
                signed.with("_hostAttestation", String.valueOf(signed.values().get("_hostAttestation"))
                        .replace("ala3.", "ala3.AA")));
        assertThat(asOther.isError()).isTrue();
        assertThat(Ledger.history(session, scenario.projectId())).hasSize(1);
    }
}
