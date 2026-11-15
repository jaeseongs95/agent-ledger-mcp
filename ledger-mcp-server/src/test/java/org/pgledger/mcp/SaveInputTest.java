package org.pgledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.attestation.ArgumentsDigest;
import org.pgledger.mcp.error.ToolError;
import org.pgledger.mcp.support.Args;
import org.pgledger.mcp.support.Calls;
import org.pgledger.mcp.support.Calls.Fields;
import org.pgledger.mcp.support.KeyRing;
import org.pgledger.mcp.support.Ledger;
import org.pgledger.mcp.support.McpServerTest;
import org.pgledger.mcp.support.McpSession;
import org.pgledger.mcp.support.McpTestSettings;
import org.pgledger.mcp.support.RawHttp;
import org.pgledger.mcp.support.RawHttp.Header;
import org.pgledger.mcp.support.Scenario;
import org.pgledger.mcp.tool.ReadOutputs;
import org.pgledger.mcp.tool.WriteOutputs;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * MCW-PRN-01: the {@code fields} of {@code ledger_save} and of a {@code save} operation of {@code ledger_batch} are one
 * input record whose fields are patches. A key written with {@code null} clears an optional field, a key left out is not
 * touched, and the receipts, the history and the error paths are those of the map this record replaced.
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

    /**
     * {@code ledger_save} of the defect with {@code fields} that holds one key written as JSON null, signed like the hook
     * signs it. The test client drops a null of a map when it writes the call (its mapper omits nulls) and the text form of
     * {@code fields} is no longer read, so the message is written here as a JSON tree, key by key, and sent as it is.
     */
    private static WriteOutputs.Saved saveWithNull(RawHttp http, McpSession session, KeyRing keys, JsonMapper mapper,
            String id, long version, String nullKey) {
        ObjectNode fields = mapper.createObjectNode();
        fields.putNull(nullKey);
        ObjectNode arguments = mapper.createObjectNode();
        String operationKey = Calls.freshKey();
        arguments.put("entity", "defect").put("id", id).put("expected_version", version)
                .put("reason", Scenario.REASON).put("operationKey", operationKey);
        arguments.set("fields", fields);
        keys.enroll(Scenario.AUTHOR);
        String digest = ArgumentsDigest.of(mapper.convertValue(arguments, new TypeReference<LinkedHashMap<String, Object>>() {
        }));
        arguments.put("_hostAttestation", keys.token(Scenario.AUTHOR, "ledger_save", operationKey, digest));
        ObjectNode params = mapper.createObjectNode();
        params.put("name", "ledger_save");
        params.set("arguments", arguments);
        ObjectNode message = mapper.createObjectNode();
        message.put("jsonrpc", "2.0").put("id", 2).put("method", "tools/call");
        message.set("params", params);
        Header bearer = Header.bearer(McpTestSettings.TOKEN);

        RawHttp.Reply reply = http.postText("/mcp", mapper.writeValueAsString(message), bearer,
                Header.session(http.openSession(bearer)));

        CallToolResult result = http.toolResult(reply);
        assertThat(result.isError()).as("the save with a null failed: %s", result.content()).isFalse();
        return session.read(result, WriteOutputs.Saved.class);
    }

    @Test
    @DisplayName("MCW-PRN-01: a key left out is not touched, a key written with null clears the optional field, and the "
            + "history of the clearing says so")
    void leavingOutAndClearing(McpSession session, RawHttp http, Scenario scenario, @Autowired KeyRing keys,
            @Autowired JsonMapper mapper) {
        WriteOutputs.Saved created = createWithOwner(session, scenario);
        assertThat(defect(session, created.id()).ownerActorId()).isEqualTo(Scenario.AUTHOR);

        WriteOutputs.Saved retitled = session.ok("ledger_save",
                edit(session, created.id(), 1, one("title", "retitled")), WriteOutputs.Saved.class);
        assertThat(defect(session, created.id()).ownerActorId()).as("a key left out is not touched")
                .isEqualTo(Scenario.AUTHOR);

        WriteOutputs.Saved cleared = saveWithNull(http, session, keys, mapper, created.id(), retitled.version(),
                "owner_actor_id");

        ReadOutputs.Defect read = defect(session, created.id());
        assertThat(read.ownerActorId()).as("a key written as null clears the field").isNull();
        assertThat(read.title()).isEqualTo("retitled");
        assertThat(cleared.version()).isEqualTo(3);
        assertThat(Ledger.history(session, scenario.projectId())).extracting(ReadOutputs.History::op)
                .containsExactly("CREATE", "EDIT", "EDIT");
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
