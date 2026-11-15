package org.pgledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.mcp.error.ToolError;
import org.pgledger.mcp.support.Args;
import org.pgledger.mcp.support.BatchResults;
import org.pgledger.mcp.support.Calls;
import org.pgledger.mcp.support.Calls.Fields;
import org.pgledger.mcp.support.Ledger;
import org.pgledger.mcp.support.McpServerTest;
import org.pgledger.mcp.support.McpSession;
import org.pgledger.mcp.support.Scenario;
import org.pgledger.mcp.support.Seed;
import org.pgledger.mcp.support.WriteCase;
import org.pgledger.mcp.tool.ReadOutputs;
import org.pgledger.mcp.tool.WriteOutputs;
import tools.jackson.databind.JsonNode;

/**
 * OPK cases of the MCP tools (section 44 of the test cases): every write tool takes a required {@code operationKey}, the
 * idempotency key of the call, and the server never makes one up. Without one, with one outside 1..128 characters, with
 * the removed arguments of the 1.x contract ({@code request_id}, {@code batch_request_id}, {@code author_id}), with an
 * object or an array sent as text, nothing is written: no history row and no receipt. The batch has one key for the whole
 * batch and its operations carry none. The cases of the attestation (OPK-010) are in {@link AttestationWireTest}, those of
 * the schemas of the upload tools (OPK-011) in {@link AttachmentCatalogTest}.
 */
@McpServerTest
class OperationKeyTest {

    private static final String KEY = "operationKey";
    private static final String UNKNOWN_ARGUMENT = "is not a known argument";

    /** A text of exactly this length that no other test uses as a key. */
    private static String keyOf(int length) {
        return (UUID.randomUUID() + "x".repeat(length)).substring(0, length);
    }

    /** The call of the case as a flat object (a fresh operationKey in it for every tool but whoami). */
    private static Args callOf(McpSession session, WriteCase write, Seed seed) {
        return Args.from(session.mapper(), write.call(seed));
    }

    /** What a call must leave as it was: the history rows of the project and the receipts of the author. */
    private record Before(int rows, long receipts) {

        static Before of(McpSession session, Scenario scenario, Seed seed, WriteCase write) {
            return new Before(Ledger.history(session, seed.project()).size(), Ledger.receiptsOf(scenario, write.actor()));
        }

        void assertUnchanged(McpSession session, Scenario scenario, Seed seed, WriteCase write) {
            assertThat(Ledger.history(session, seed.project())).as("history rows of %s", write.tool()).hasSize(rows);
            assertThat(Ledger.receiptsOf(scenario, write.actor())).as("receipts of %s", write.actor()).isEqualTo(receipts);
        }
    }

    // ---------------------------------------------------------------------------------------------- OPK-001

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.pgledger.mcp.support.WriteCase#keyed")
    @DisplayName("OPK-001: each of the nine write tools called without operationKey is VALIDATION_FAILED at operationKey "
            + "(is required) and writes nothing: no history row, no receipt")
    void noWriteWithoutAnOperationKey(WriteCase write, McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);
        Before before = Before.of(session, scenario, seed, write);
        Args call = callOf(session, write, seed).without(KEY);

        ToolError error = session.failed(write.tool(), call);

        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.issues()).containsExactly(new ToolError.Issue(KEY, "is required", List.of()));
        before.assertUnchanged(session, scenario, seed, write);
    }

    // ---------------------------------------------------------------------------------------------- OPK-002

    /** A write tool and an operation key that is not 1..128 characters. */
    private record BadKey(WriteCase write, String label, String key) {

        @Override
        public String toString() {
            return write.tool() + " with a key that is " + label;
        }
    }

    static Stream<BadKey> badKeys() {
        return WriteCase.keyed().stream().flatMap(write -> Stream.of(
                new BadKey(write, "empty", ""),
                new BadKey(write, "blank", "   "),
                new BadKey(write, "129 characters", keyOf(129))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badKeys")
    @DisplayName("OPK-002: each of the nine write tools with an operationKey that is empty, blank or 129 characters is "
            + "VALIDATION_FAILED at operationKey (must be 1..128 characters) and writes nothing")
    void aKeyOutsideTheRangeIsRefused(BadKey bad, McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);
        Before before = Before.of(session, scenario, seed, bad.write());
        Args call = callOf(session, bad.write(), seed).with(KEY, bad.key());

        ToolError error = session.failed(bad.write().tool(), call);

        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.issues()).containsExactly(new ToolError.Issue(KEY, "must be 1..128 characters", List.of()));
        before.assertUnchanged(session, scenario, seed, bad.write());
        assertThat(Ledger.receipts(scenario, bad.key())).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.pgledger.mcp.support.WriteCase#keyed")
    @DisplayName("OPK-002: an operationKey of exactly 128 characters is accepted by each of the nine write tools and is "
            + "the key of the receipt")
    void aKeyOf128CharactersIsAccepted(WriteCase write, McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);
        String key = keyOf(128);

        CallToolResult result = session.call(write.tool(), callOf(session, write, seed).with(KEY, key));

        assertThat(result.isError()).as("%s with a key of 128 characters: %s", write.tool(), result.content()).isFalse();
        assertThat(Ledger.receipts(scenario, key)).isOne();
    }

    // ---------------------------------------------------------------------------------------------- OPK-005 (wire)

    /** An operation of a batch and a key that the operation must not carry. */
    private record OpWithKey(String op, Calls.Op operation, String key) {

        @Override
        public String toString() {
            return op + " with " + key;
        }
    }

    static Stream<OpWithKey> operationsWithAKey() {
        return Stream.of(
                new OpWithKey("save", Calls.Op.save("defect", Fields.defect("p", "t")), null),
                new OpWithKey("transition", Calls.Op.transition("defect", "D-1", 1, "DISPUTED"), null),
                new OpWithKey("record_fix", Calls.Op.recordFix("D-1", "a".repeat(40)), null),
                new OpWithKey("record_case", Calls.Op.recordCase("D-1", "seen"), null),
                new OpWithKey("link", Calls.Op.link("defect_task", "D-1", "T-1"), null),
                new OpWithKey("correct", Calls.Op.correct(1), null))
                .flatMap(op -> Stream.of(KEY, "request_id").map(key -> new OpWithKey(op.op(), op.operation(), key)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("operationsWithAKey")
    @DisplayName("OPK-005: an operation of a batch carries no key: an operationKey or a request_id inside operation i is "
            + "refused as unknown at ops[i].<key> and nothing is written")
    void anOperationCarriesNoKey(OpWithKey given, McpSession session, Scenario scenario) {
        String batchKey = Calls.freshKey();
        List<Object> ops = List.of(Args.from(session.mapper(), Calls.Op.recordCase("D-1", "first")),
                Args.from(session.mapper(), given.operation()).with(given.key(), UUID.randomUUID().toString()));
        Args call = Args.from(session.mapper(), new Calls.Batch(List.of(), batchKey, Scenario.AUTHOR)).with("ops", ops);

        ToolError error = session.failed("ledger_batch", call);

        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.issues()).containsExactly(
                new ToolError.Issue("ops[1]." + given.key(), UNKNOWN_ARGUMENT, List.of()));
        assertThat(Ledger.history(session, scenario.projectId())).isEmpty();
        assertThat(Ledger.receipts(scenario, batchKey)).isZero();
    }

    @Test
    @DisplayName("OPK-005: one operationKey for the batch: the same key with the same operations returns the stored "
            + "results and writes once; the same key with other operations is OPERATION_CONFLICT and writes nothing more")
    void theBatchKeyIsReplayed(McpSession session, Scenario scenario) {
        String batchKey = Calls.freshKey();
        List<Calls.Op> ops = List.of(Calls.Op.save("defect", Fields.defect(scenario.projectId(), "first operation")),
                Calls.Op.save("task", Fields.task(scenario.projectId(), "second operation")));

        BatchResults first = session.ok("ledger_batch", new Calls.Batch(ops, batchKey, Scenario.AUTHOR),
                BatchResults.class);
        BatchResults again = session.ok("ledger_batch", new Calls.Batch(ops, batchKey, Scenario.AUTHOR),
                BatchResults.class);
        ToolError other = session.failed("ledger_batch", new Calls.Batch(
                List.of(Calls.Op.save("defect", Fields.defect(scenario.projectId(), "another operation"))), batchKey,
                Scenario.AUTHOR));

        assertThat(first.count()).isEqualTo(2);
        assertThat(again).isEqualTo(first);
        assertThat(other.code()).isEqualTo("OPERATION_CONFLICT");
        assertThat(Ledger.history(session, scenario.projectId())).hasSize(2);
        assertThat(Ledger.receipts(scenario, batchKey)).isOne();
    }

    // ---------------------------------------------------------------------------------------------- OPK-006

    /** A tool and an argument of the 1.x contract that it no longer takes. */
    private record Removed(WriteCase write, String name) {

        @Override
        public String toString() {
            return write.tool() + " with " + name;
        }
    }

    static Stream<Removed> removedArguments() {
        return WriteCase.all().stream().flatMap(write -> Stream.of("request_id", "batch_request_id", "author_id")
                .map(name -> new Removed(write, name)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("removedArguments")
    @DisplayName("OPK-006: request_id, batch_request_id and author_id (each alone, next to a valid operationKey) are "
            + "refused as UNKNOWN_ARGUMENT by every write tool and by ledger_whoami, and nothing is written")
    void aRemovedArgumentIsUnknown(Removed removed, McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);
        Before before = Before.of(session, scenario, seed, removed.write());
        Args call = callOf(session, removed.write(), seed).with(removed.name(), UUID.randomUUID().toString());

        ToolError error = session.failed(removed.write().tool(), call);

        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.issues()).containsExactly(new ToolError.Issue(removed.name(), UNKNOWN_ARGUMENT, List.of()));
        before.assertUnchanged(session, scenario, seed, removed.write());
    }

    // ---------------------------------------------------------------------------------------------- OPK-007

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.pgledger.mcp.support.WriteCase#keyed")
    @DisplayName("OPK-007: a write with request_id only (no operationKey) is VALIDATION_FAILED, operationKey is required "
            + "and request_id is not a known argument, and writes nothing: a 1.x retry with the old key cannot cause a write")
    void theOldKeyAloneIsRefused(WriteCase write, McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);
        Before before = Before.of(session, scenario, seed, write);
        String oldKey = UUID.randomUUID().toString();
        Args call = callOf(session, write, seed).without(KEY).with("request_id", oldKey);

        ToolError error = session.failed(write.tool(), call);

        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.issues()).containsExactlyInAnyOrder(
                new ToolError.Issue("request_id", UNKNOWN_ARGUMENT, List.of()),
                new ToolError.Issue(KEY, "is required", List.of()));
        before.assertUnchanged(session, scenario, seed, write);
        assertThat(Ledger.receipts(scenario, oldKey)).isZero();
    }

    // ---------------------------------------------------------------------------------------------- OPK-008

    /** A call that sends an object or an array as text, and the refusal that is the answer. */
    private record TextForm(String name, String tool, BiFunction<McpSession, Scenario, Args> call, ToolError.Issue issue) {

        @Override
        public String toString() {
            return name;
        }
    }

    private static Args saveWith(McpSession session, Scenario scenario, Object fields) {
        return Args.from(session.mapper(),
                Calls.Save.create("defect", Fields.defect(scenario.projectId(), "as text"), Scenario.AUTHOR))
                .with("fields", fields);
    }

    private static Args batchWith(McpSession session, Scenario scenario, Object ops) {
        return Args.from(session.mapper(), Calls.Batch.of(Scenario.AUTHOR,
                Calls.Op.save("defect", Fields.defect(scenario.projectId(), "as text")))).with("ops", ops);
    }

    static Stream<TextForm> textForms() {
        ToolError.Issue fields = new ToolError.Issue("fields", "must be of type SaveFields", List.of());
        ToolError.Issue ops = new ToolError.Issue("ops", "must be of type List", List.of());
        return Stream.of(
                new TextForm("fields as a JSON object text", "ledger_save", (session, scenario) -> saveWith(session,
                        scenario, session.mapper().writeValueAsString(Fields.defect(scenario.projectId(), "as text"))),
                        fields),
                new TextForm("fields as the text null", "ledger_save",
                        (session, scenario) -> saveWith(session, scenario, "null"), fields),
                new TextForm("ops as a JSON array text", "ledger_batch", (session, scenario) -> batchWith(session,
                        scenario, session.mapper().writeValueAsString(
                                List.of(Calls.Op.save("defect", Fields.defect(scenario.projectId(), "as text"))))),
                        ops),
                new TextForm("ops as the text null", "ledger_batch",
                        (session, scenario) -> batchWith(session, scenario, "null"), ops));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("textForms")
    @DisplayName("OPK-008: an object or an array sent as a JSON string (fields, ops), and the text null for such a "
            + "required parameter, is VALIDATION_FAILED with the wrong type (not a missing argument) and writes nothing")
    void anObjectAsTextIsTheWrongType(TextForm form, McpSession session, Scenario scenario) {
        Args call = form.call().apply(session, scenario);

        ToolError error = session.failed(form.tool(), call);

        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.issues()).containsExactly(form.issue());
        assertThat(Ledger.history(session, scenario.projectId())).isEmpty();
        assertThat(Ledger.receipts(scenario, (String) call.values().get(KEY))).isZero();
    }

    @Test
    @DisplayName("OPK-008: the text null for a String parameter is the text null, not a missing argument: a reason of four "
            + "characters is REASON_INVALID")
    void theTextNullIsAPlainText(McpSession session, Scenario scenario) {
        Args call = Args.from(session.mapper(),
                Calls.Save.create("defect", Fields.defect(scenario.projectId(), "plain text"), Scenario.AUTHOR))
                .with("reason", "null");

        ToolError error = session.failed("ledger_save", call);

        assertThat(error.code()).isEqualTo("REASON_INVALID");
        assertThat(error.issues()).extracting(ToolError.Issue::path).containsExactly("reason");
        assertThat(Ledger.history(session, scenario.projectId())).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------- OPK-012

    @Test
    @DisplayName("OPK-012: the history read output has operation_key and no request_id; its value is the key of the write, "
            + "and for the operations of a batch a key derived from the batch key (one per operation, none of them the batch key)")
    void theHistoryShowsTheOperationKey(McpSession session, Scenario scenario) {
        String project = scenario.projectId();
        String single = Calls.freshKey();
        String batchKey = Calls.freshKey();
        session.ok("ledger_save", Calls.Save.create("defect", Fields.defect(project, "single"), Scenario.AUTHOR)
                .withOperationKey(single), WriteOutputs.Saved.class);
        session.ok("ledger_batch", new Calls.Batch(List.of(Calls.Op.save("defect", Fields.defect(project, "batched 1")),
                Calls.Op.save("defect", Fields.defect(project, "batched 2"))), batchKey, Scenario.AUTHOR),
                BatchResults.class);

        JsonNode items = session.mapper().valueToTree(session.call("ledger_history", Calls.History.of(project))
                .structuredContent()).get("items");
        List<String> keys = Ledger.history(session, project).stream().map(ReadOutputs.History::operationKey).toList();

        assertThat(items.size()).isEqualTo(3);
        for (JsonNode row : items) {
            assertThat(row.has("operation_key")).as("every row has operation_key: %s", row).isTrue();
            assertThat(row.has("request_id")).as("no row has request_id: %s", row).isFalse();
        }
        assertThat(keys.getFirst()).isEqualTo(single);
        List<String> derived = keys.subList(1, 3);
        assertThat(derived).doesNotHaveDuplicates().doesNotContain(batchKey, single);
        // the derived key is the text of a name-based UUID (it is no part of the batch key)
        assertThat(derived).allSatisfy(key -> assertThat(UUID.fromString(key)).isNotNull());
    }
}
