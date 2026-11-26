// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.veridger.mcp.support.Args;
import org.veridger.mcp.support.Calls;
import org.veridger.mcp.support.HookProcess;
import org.veridger.mcp.support.Ledger;
import org.veridger.mcp.support.McpServerTest;
import org.veridger.mcp.support.McpSession;
import org.veridger.mcp.support.Scenario;
import org.veridger.mcp.tool.WriteOutputs;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * MCW-040: end-to-end round trip with the built hook jar. The owner grants the session directly, the hook jar stamps
 * {@code _hostAttestation} with {@code <vendor>:<session_id>} (and denies any model-supplied {@code _hostAttestation}),
 * and the MCP Java SDK client sends exactly what the hook returned.
 */
@McpServerTest
class HookRoundTripTest {

    private static final TypeReference<LinkedHashMap<String, Object>> OBJECT = new TypeReference<>() {
    };

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
    @DisplayName("MCW-040: grant -> hook stamps plain <vendor>:<session_id> -> MCP write & whoami succeed; forged _hostAttestation is denied by the hook")
    void roundTrip(McpSession session, Scenario scenario, @Autowired JsonMapper mapper, @TempDir Path work)
            throws IOException {
        Path keys = work.resolve("keys");
        residentWork = work;
        residentKeys = keys;
        String hostId = "rt" + UUID.randomUUID().toString().substring(0, 8);
        String author = "claude:" + hostId;

        // 1. owner grants the session directly (no session-key or token issuance)
        scenario.grant(author, "issue");

        // 2. model write call stamped by the real hook jar
        Calls.IssueSave call = Calls.IssueSave.create(scenario.projectId(), "round trip", null);
        Args stamped = hooked(mapper, work, keys, "ledger_issue_save", hostId, call);
        assertThat(stamped.values()).containsKeys("_hostAttestation", "operationKey");
        assertThat(stamped.values().get("operationKey")).isEqualTo(call.operationKey());
        assertThat(stamped.without("_hostAttestation").values())
                .isEqualTo(Args.from(mapper, call).values());
        assertThat(stamped.values().get("_hostAttestation")).isEqualTo(author);

        WriteOutputs.Saved saved = session.ok("ledger_issue_save", stamped, WriteOutputs.Saved.class);
        assertThat(saved.author()).isEqualTo(author);
        assertThat(saved.version()).isEqualTo(1);
        assertThat(Ledger.history(session, scenario.projectId())).hasSize(1);

        // 3. whoami stamped by the hook jar
        Args whoami = hooked(mapper, work, keys, "ledger_whoami", hostId, Map.of());
        assertThat(whoami.values()).containsOnlyKeys("_hostAttestation");
        assertThat(whoami.values().get("_hostAttestation")).isEqualTo(author);
        WriteOutputs.Whoami who = session.ok("ledger_whoami", whoami, WriteOutputs.Whoami.class);
        assertThat(who.author()).isEqualTo(author);
        assertThat(who.grants()).hasSize(1);

        // 4. idempotent replay and conflict on modified payload under same operationKey
        assertThat(session.ok("ledger_issue_save", stamped, WriteOutputs.Saved.class)).isEqualTo(saved);
        assertThat(session.failed("ledger_issue_save", stamped.with("title", "other content")).code())
                .isEqualTo("OPERATION_CONFLICT");

        // 5. anti-spoofing: a model-supplied _hostAttestation is denied by the hook before reaching the server
        String forgedEvent = mapper.writeValueAsString(Map.of(
                "tool_name", "mcp__ledger__ledger_issue_save",
                "tool_input", Map.of("_hostAttestation", "claude:other-session"),
                "session_id", hostId));
        HookProcess.Result denied = HookProcess.run(work, keys, forgedEvent, "claude");
        assertThat(denied.exit()).isZero();
        assertThat(mapper.readTree(denied.out()).path("hookSpecificOutput").path("permissionDecision").asString())
                .isEqualTo("deny");
        assertThat(Ledger.history(session, scenario.projectId())).hasSize(1);
    }
}
