package org.pgledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.mcp.error.ToolError;
import org.pgledger.mcp.support.Args;
import org.pgledger.mcp.support.Calls;
import org.pgledger.mcp.support.Calls.Fields;
import org.pgledger.mcp.support.Ledger;
import org.pgledger.mcp.support.McpServerTest;
import org.pgledger.mcp.support.McpSession;
import org.pgledger.mcp.support.McpTestSettings;
import org.pgledger.mcp.support.RawHttp;
import org.pgledger.mcp.support.RawHttp.Header;
import org.pgledger.mcp.support.Scenario;
import org.pgledger.mcp.tool.ReadOutputs;
import org.pgledger.mcp.tool.WriteOutputs;

/**
 * MCW-PRN-01: the {@code fields} of {@code ledger_save} and of a {@code save} operation of {@code ledger_batch} are one
 * input record whose fields are patches. A key written blank clears an optional field (with {@code Patch.set(null)} made in
 * code it clears too), a key left out or written as JSON {@code null} is not touched (a null counts as not given), and the
 * receipts, the history and the error paths are those of the map this record replaced.
 */
@McpServerTest
class SaveInputTest {

    private static ReadOutputs.Defect defect(McpSession session, String id) {
        return session.ok("ledger_defect", new Calls.IdOnly(id), ReadOutputs.Defect.class);
    }

    private static WriteOutputs.Saved createWithOwner(McpSession session, Scenario scenario) {
        Fields withOwner = new Fields(scenario.projectId(), "owned", "ARC", "P2", "behavior", "cause", Scenario.AUTHOR,
                null, null, null, null, null, null, null, null);
        return session.ok("ledger_save", Calls.Save.create("defect", withOwner, Scenario.AUTHOR),
                WriteOutputs.Saved.class);
    }

    private static Args edit(McpSession session, String id, long version, Object fields) {
        return Args.from(session.mapper(), Calls.Save.edit("defect", id, version, Fields.titled("x"), Scenario.AUTHOR))
                .with("fields", fields);
    }

    /** The {@code fields} object of one key; the value may be null (a key written as null). */
    private static Map<String, Object> one(String key, Object value) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(key, value);
        return fields;
    }

    /** The {@code fields} object of two keys; a value may be null (a key written as null). */
    private static Map<String, Object> two(String key, Object value, String other, Object otherValue) {
        Map<String, Object> fields = one(key, value);
        fields.put(other, otherValue);
        return fields;
    }

    /** The call over the real transport (HTTP to the running server, a null of the arguments goes on the wire as it is). */
    private static CallToolResult overTheWire(RawHttp http, Args call) {
        Header bearer = Header.bearer(McpTestSettings.TOKEN);
        String sessionId = http.openSession(bearer);
        return http.toolResult(http.callTool(sessionId, "ledger_save", call, bearer));
    }

    @Test
    @DisplayName("MCW-PRN-01: a key left out is not touched, a key written with JSON null counts as not given (changed by opkey "
            + "round 2: null used to clear; the digest of the signed arguments leaves the null out, so there is no "
            + "ATTESTATION_BINDING_MISMATCH), a key written blank clears the optional field, and the history says so (OPK-021)")
    void leavingOutAndClearing(McpSession session, RawHttp http, Scenario scenario) {
        WriteOutputs.Saved created = createWithOwner(session, scenario);
        assertThat(defect(session, created.id()).ownerActorId()).isEqualTo(Scenario.AUTHOR);

        WriteOutputs.Saved retitled = session.ok("ledger_save",
                edit(session, created.id(), 1, one("title", "retitled")), WriteOutputs.Saved.class);
        assertThat(defect(session, created.id()).ownerActorId()).as("a key left out is not touched")
                .isEqualTo(Scenario.AUTHOR);

        // JSON null in fields counts as not given: the title is written, the owner is not touched (signed with the digest of
        // the arguments as sent, which leaves the null member out like the server side of the MCP SDK does)
        Args withNull = edit(session, created.id(), retitled.version(), two("owner_actor_id", null, "title", "x"));
        assertThat(http.toolCallText("ledger_save", withNull)).as("the null is on the wire")
                .contains("\"owner_actor_id\":null");
        CallToolResult nullAndTitle = overTheWire(http, withNull);
        assertThat(nullAndTitle.isError()).as("null next to a title: %s", nullAndTitle.content()).isFalse();
        ReadOutputs.Defect afterNull = defect(session, created.id());
        assertThat(afterNull.title()).isEqualTo("x");
        assertThat(afterNull.ownerActorId()).as("a null is not a clearing").isEqualTo(Scenario.AUTHOR);
        assertThat(afterNull.version()).isEqualTo(3);

        // an empty text clears an optional field, the one way
        WriteOutputs.Saved cleared = session.ok("ledger_save",
                edit(session, created.id(), afterNull.version(), one("owner_actor_id", "")), WriteOutputs.Saved.class);

        ReadOutputs.Defect read = defect(session, created.id());
        assertThat(read.ownerActorId()).as("a key written blank clears the field").isNull();
        assertThat(read.title()).isEqualTo("x");
        assertThat(cleared.version()).isEqualTo(4);
        assertThat(Ledger.history(session, scenario.projectId())).extracting(ReadOutputs.History::op)
                .containsExactly("CREATE", "EDIT", "EDIT", "EDIT");
    }

    @Test
    @DisplayName("MCW-PRN-01 / OPK-021: fields with a JSON null alone is VALIDATION_FAILED at fields (must mention at least one "
            + "field), as null counts as not given, over the real transport, and nothing is written")
    void aNullAloneMentionsNoField(McpSession session, RawHttp http, Scenario scenario) {
        WriteOutputs.Saved created = createWithOwner(session, scenario);

        CallToolResult result = overTheWire(http, edit(session, created.id(), 1, one("owner_actor_id", null)));

        assertThat(result.isError()).as("a null alone must be refused: %s", result.content()).isTrue();
        ToolError error = session.error(result);
        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.issues()).containsExactly(new ToolError.Issue("fields", "must mention at least one field", List.of()));
        ReadOutputs.Defect read = defect(session, created.id());
        assertThat(read.version()).isEqualTo(1);
        assertThat(read.ownerActorId()).isEqualTo(Scenario.AUTHOR);
        assertThat(Ledger.history(session, scenario.projectId())).hasSize(1);
    }

    @Test
    @DisplayName("MCW-PRN-01: a key that belongs to another entity (purpose of a defect) is VALIDATION_FAILED on "
            + "fields.purpose and nothing is written")
    void aKeyOfAnotherEntity(McpSession session, Scenario scenario) {
        WriteOutputs.Saved created = createWithOwner(session, scenario);

        ToolError error = session.failed("ledger_save",
                edit(session, created.id(), 1, one("purpose", "not a defect field")));

        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.issues()).extracting(ToolError.Issue::path).containsExactly("fields.purpose");
        assertThat(defect(session, created.id()).version()).isEqualTo(1);
    }

    @Test
    @DisplayName("MCW-PRN-01: an unknown key in a batch save is VALIDATION_FAILED UNKNOWN_ARGUMENT on "
            + "ops[<i>].fields.<key> (the batch binds fields as the same record as ledger_save), nothing is written")
    void anUnknownKeyInABatch(McpSession session, Scenario scenario) {
        Args call = Args.from(session.mapper(), Calls.Save.create("defect",
                Fields.defect(scenario.projectId(), "t"), Scenario.AUTHOR));
        Args batch = Args.of().with("_hostAttestation", call.values().get("_hostAttestation"))
                .with("operationKey", Calls.freshKey()).with("ops", List.of(
                Args.of().with("op", "save").with("entity", "defect").with("reason", Scenario.REASON)
                        .with("fields", Args.of().with("title", "t").with("titel", "x"))));

        ToolError error = session.failed("ledger_batch", batch);

        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.issues()).extracting(ToolError.Issue::path).containsExactly("ops[0].fields.titel");
        assertThat(Ledger.history(session, scenario.projectId())).isEmpty();
    }

    @Test
    @DisplayName("MCW-PRN-01: the old text form of fields is refused as the wrong type, whatever the text holds (changed: "
            + "it used to bind with the same patches), and nothing is written")
    void theTextFormOfFieldsIsRefused(McpSession session, Scenario scenario) {
        WriteOutputs.Saved created = createWithOwner(session, scenario);

        ToolError error = session.failed("ledger_save", edit(session, created.id(), 1, null)
                .with("fields", "{\"title\":\"from text\",\"owner_actor_id\":null}"));

        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.issues()).containsExactly(new ToolError.Issue("fields", "must be of type SaveFields", List.of()));
        ReadOutputs.Defect read = defect(session, created.id());
        assertThat(read.title()).isEqualTo("owned");
        assertThat(read.ownerActorId()).isEqualTo(Scenario.AUTHOR);
        assertThat(read.version()).isEqualTo(1);
    }
}
