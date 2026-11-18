package org.pgledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.mcp.error.ToolError;
import org.pgledger.mcp.support.Calls;
import org.pgledger.mcp.support.McpServerTest;
import org.pgledger.mcp.support.McpSession;
import org.pgledger.mcp.support.Scenario;
import org.pgledger.mcp.tool.ReadOutputs;
import org.pgledger.mcp.tool.WriteOutputs;

/** REH3-MCP-01: the tools and free-text ids (a task or spec with a mixed-case id, the schemas of the tools). */
@McpServerTest
class FreeTextIdsTest {

    @Test
    @DisplayName("REH3-MCP-01 no input schema of any tool has a pattern for an id (the id arguments are plain strings)")
    void noSchemaHasAPattern(McpSession session) {
        for (Tool tool : session.tools()) {
            assertThat(session.mapper().writeValueAsString(tool.inputSchema())).as(tool.name()).doesNotContain("\"pattern\"");
        }
    }

    @Test
    @DisplayName("REH3-MCP-01 a task and spec with free-text ids are created, read and edited by those ids through the tools; the same id in the other case is NOT_FOUND")
    void aFreeTextIdWorksThroughTheTools(McpSession session, Scenario scenario) {
        String id = scenario.code() + ":A07-a";
        String other = id.toLowerCase(Locale.ROOT);

        WriteOutputs.Saved created = session.ok("ledger_task_save",
                Calls.TaskSave.create(scenario.projectId(), "a free-text id", Scenario.AUTHOR).withId(id),
                WriteOutputs.Saved.class);

        assertThat(created.id()).isEqualTo(id);
        assertThat(session.ok("ledger_task", new Calls.IdOnly(id), ReadOutputs.Task.class).id()).isEqualTo(id);
        WriteOutputs.Saved edited = session.ok("ledger_task_save",
                Calls.TaskSave.editTitle(id, 1, "edited", Scenario.AUTHOR), WriteOutputs.Saved.class);
        assertThat(edited.version()).isEqualTo(2);
        ToolError missing = session.failed("ledger_task", new Calls.IdOnly(other));
        assertThat(missing.code()).isEqualTo("NOT_FOUND");
        assertThat(session.failed("ledger_task_save",
                Calls.TaskSave.editTitle(other, 1, "edited", Scenario.AUTHOR)).code()).isEqualTo("NOT_FOUND");

        String specId = scenario.code() + ":SPEC-01a";
        WriteOutputs.Saved specCreated = session.ok("ledger_spec_save",
                Calls.SpecSave.create(scenario.projectId(), "a free-text spec", Scenario.AUTHOR).withId(specId),
                WriteOutputs.Saved.class);
        assertThat(specCreated.id()).isEqualTo(specId);
        assertThat(session.ok("ledger_spec", new Calls.IdOnly(specId), ReadOutputs.Spec.class).id()).isEqualTo(specId);
    }
}
