// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.mcp.error.ToolError;
import org.veridger.mcp.support.Args;
import org.veridger.mcp.support.Calls;
import org.veridger.mcp.support.Ledger;
import org.veridger.mcp.support.McpServerTest;
import org.veridger.mcp.support.McpSession;
import org.veridger.mcp.support.McpTestSettings;
import org.veridger.mcp.support.RawHttp;
import org.veridger.mcp.support.RawHttp.Header;
import org.veridger.mcp.support.Scenario;
import org.veridger.mcp.tool.ReadOutputs;
import org.veridger.mcp.tool.WriteOutputs;

/**
 * MCW-PRN-01: flat save tools ({@code ledger_issue_save}, {@code ledger_spec_save}, {@code ledger_task_save},
 * {@code ledger_criterion_save}) and {@code save} operations of {@code ledger_batch} treat omitted or JSON {@code null}
 * values as untouched and an empty string {@code ""} as clearing an optional field.
 */
@McpServerTest
class SaveInputTest {

    private static ReadOutputs.Issue issue(McpSession session, String id) {
        return session.ok("ledger_issue", new Calls.IdOnly(id), ReadOutputs.Issue.class);
    }

    private static WriteOutputs.Saved createWithOwner(McpSession session, Scenario scenario) {
        return session.ok("ledger_issue_save",
                Calls.IssueSave.create(scenario.projectId(), "owned", Scenario.AUTHOR).withOwner(Scenario.AUTHOR),
                WriteOutputs.Saved.class);
    }

    private static Args flatEdit(McpSession session, String id, long version) {
        return Args.from(session.mapper(), Calls.IssueSave.editTitle(id, version, "x", Scenario.AUTHOR))
                .without("title");
    }

    /** The call over the real transport (HTTP to the running server, a null of the arguments goes on the wire as it is). */
    private static CallToolResult overTheWire(RawHttp http, String tool, Args call) {
        Header bearer = Header.bearer(McpTestSettings.TOKEN);
        String sessionId = http.openSession(bearer);
        return http.toolResult(http.callTool(sessionId, tool, call, bearer));
    }

    @Test
    @DisplayName("MCW-PRN-01: a parameter left out is not touched, a parameter written with JSON null counts as not given, "
            + "and an empty string clears an optional field with full history")
    void leavingOutAndClearing(McpSession session, RawHttp http, Scenario scenario) {
        WriteOutputs.Saved created = createWithOwner(session, scenario);
        assertThat(issue(session, created.id()).ownerActorId()).isEqualTo(Scenario.AUTHOR);

        WriteOutputs.Saved retitled = session.ok("ledger_issue_save",
                Calls.IssueSave.editTitle(created.id(), 1, "retitled", Scenario.AUTHOR), WriteOutputs.Saved.class);
        assertThat(issue(session, created.id()).ownerActorId()).as("a parameter left out is not touched")
                .isEqualTo(Scenario.AUTHOR);

        Args withNull = flatEdit(session, created.id(), retitled.version())
                .with("owner_actor_id", null).with("title", "x");
        assertThat(http.toolCallText("ledger_issue_save", withNull)).as("the null is on the wire")
                .contains("\"owner_actor_id\":null");
        CallToolResult nullAndTitle = overTheWire(http, "ledger_issue_save", withNull);
        assertThat(nullAndTitle.isError()).as("null next to a title: %s", nullAndTitle.content()).isFalse();
        ReadOutputs.Issue afterNull = issue(session, created.id());
        assertThat(afterNull.title()).isEqualTo("x");
        assertThat(afterNull.ownerActorId()).as("a null is not a clearing").isEqualTo(Scenario.AUTHOR);
        assertThat(afterNull.version()).isEqualTo(3);

        WriteOutputs.Saved cleared = session.ok("ledger_issue_save",
                flatEdit(session, created.id(), afterNull.version()).with("owner_actor_id", ""),
                WriteOutputs.Saved.class);

        ReadOutputs.Issue read = issue(session, created.id());
        assertThat(read.ownerActorId()).as("an empty string clears the field").isNull();
        assertThat(read.title()).isEqualTo("x");
        assertThat(cleared.version()).isEqualTo(4);
        assertThat(Ledger.history(session, scenario.projectId())).extracting(ReadOutputs.History::op)
                .containsExactly("CREATE", "EDIT", "EDIT", "EDIT");
    }

    @Test
    @DisplayName("MCW-PRN-01 / OPK-021: an edit with a JSON null alone mentions no field and is VALIDATION_FAILED over "
            + "the real transport, and nothing is written")
    void aNullAloneMentionsNoField(McpSession session, RawHttp http, Scenario scenario) {
        WriteOutputs.Saved created = createWithOwner(session, scenario);

        CallToolResult result = overTheWire(http, "ledger_issue_save",
                flatEdit(session, created.id(), 1).with("owner_actor_id", null));

        assertThat(result.isError()).as("a null alone must be refused: %s", result.content()).isTrue();
        ToolError error = session.error(result);
        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.issues()).containsExactly(new ToolError.Issue("fields", "must mention at least one field", List.of()));
        ReadOutputs.Issue read = issue(session, created.id());
        assertThat(read.version()).isEqualTo(1);
        assertThat(read.ownerActorId()).isEqualTo(Scenario.AUTHOR);
        assertThat(Ledger.history(session, scenario.projectId())).hasSize(1);
    }

    @Test
    @DisplayName("MCW-PRN-01: a parameter belonging to another entity (purpose on ledger_issue_save, and fields.purpose "
            + "in a batch issue save) is VALIDATION_FAILED and nothing is written")
    void aKeyOfAnotherEntity(McpSession session, Scenario scenario) {
        WriteOutputs.Saved created = createWithOwner(session, scenario);

        ToolError flatError = session.failed("ledger_issue_save",
                flatEdit(session, created.id(), 1).with("purpose", "not an issue parameter"));
        assertThat(flatError.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(flatError.issues()).extracting(ToolError.Issue::path).containsExactly("purpose");

        Map<String, Object> batchFields = new LinkedHashMap<>();
        batchFields.put("purpose", "not an issue field");
        Args batch = Args.of().with("_hostAttestation", Scenario.AUTHOR)
                .with("operationKey", Calls.freshKey()).with("ops", List.of(
                        Args.of().with("op", "save").with("entity", "issue").with("id", created.id())
                                .with("expected_version", 1L).with("reason", Scenario.REASON)
                                .with("fields", batchFields)));
        ToolError batchError = session.failed("ledger_batch", batch);
        assertThat(batchError.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(batchError.issues()).extracting(ToolError.Issue::path).containsExactly("fields.purpose");
        assertThat(issue(session, created.id()).version()).isEqualTo(1);
    }

    @Test
    @DisplayName("MCW-PRN-01: an unknown key in a batch save is VALIDATION_FAILED UNKNOWN_ARGUMENT on "
            + "ops[<i>].fields.<key>, nothing is written")
    void anUnknownKeyInABatch(McpSession session, Scenario scenario) {
        Args batch = Args.of().with("_hostAttestation", Scenario.AUTHOR)
                .with("operationKey", Calls.freshKey()).with("ops", List.of(
                        Args.of().with("op", "save").with("entity", "issue").with("reason", Scenario.REASON)
                                .with("fields", Args.of().with("title", "t").with("titel", "x"))));

        ToolError error = session.failed("ledger_batch", batch);

        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.issues()).extracting(ToolError.Issue::path).containsExactly("ops[0].fields.titel");
        assertThat(Ledger.history(session, scenario.projectId())).isEmpty();
    }

    @Test
    @DisplayName("MCW-PRN-01: the text form of fields in a batch save is refused as the wrong type, and nothing is written")
    void theTextFormOfFieldsIsRefused(McpSession session, Scenario scenario) {
        WriteOutputs.Saved created = createWithOwner(session, scenario);

        Args batch = Args.of().with("_hostAttestation", Scenario.AUTHOR)
                .with("operationKey", Calls.freshKey()).with("ops", List.of(
                        Args.of().with("op", "save").with("entity", "issue").with("id", created.id())
                                .with("expected_version", 1L).with("reason", Scenario.REASON)
                                .with("fields", "{\"title\":\"from text\",\"owner_actor_id\":null}")));
        ToolError error = session.failed("ledger_batch", batch);

        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.issues()).containsExactly(
                new ToolError.Issue("ops[0].fields", "must be of type SaveFields", List.of()));
        ReadOutputs.Issue read = issue(session, created.id());
        assertThat(read.title()).isEqualTo("owned");
        assertThat(read.ownerActorId()).isEqualTo(Scenario.AUTHOR);
        assertThat(read.version()).isEqualTo(1);
    }
}
