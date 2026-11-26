// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.veridger.mcp.error.ToolError;
import org.veridger.mcp.support.Args;
import org.veridger.mcp.support.BatchResults;
import org.veridger.mcp.support.Calls;
import org.veridger.mcp.support.Calls.Fields;
import org.veridger.mcp.support.Ledger;
import org.veridger.mcp.support.McpServerTest;
import org.veridger.mcp.support.McpSession;
import org.veridger.mcp.support.Scenario;
import org.veridger.mcp.support.SchemaView;
import org.veridger.mcp.support.Seed;
import org.veridger.mcp.support.WriteCase;
import org.veridger.mcp.tool.ReadOutputs;
import org.veridger.mcp.tool.WriteOutputs;
import org.veridger.testing.ContractVectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * OPK cases of the MCP tools (section 44 of the test cases): every write tool takes a required {@code operationKey}, the
 * idempotency key of the call, and the server never makes one up. Without one, with one outside 1..128 characters, with
 * the removed arguments of the 1.x contract ({@code request_id}, {@code batch_request_id}, {@code author_id}), with an
 * object or an array sent as text, nothing is written: no history row and no receipt. The batch has one key for the whole
 * batch and its operations carry none; the batch holds a receipt of its own (operation BATCH) under its key, so the same key
 * with other operations or under another tool is OPERATION_CONFLICT (OPK-019), and the receipts are kept per author (OPK-020).
 * The published schemas follow the contract vectors (OPK-022). The cases of the attestation (OPK-010) are in
 * {@link AttestationWireTest}, those of the schemas of the upload tools (OPK-011) in {@link AttachmentCatalogTest}.
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
    @MethodSource("org.veridger.mcp.support.WriteCase#keyed")
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
    @MethodSource("org.veridger.mcp.support.WriteCase#keyed")
    @DisplayName("OPK-002: an operationKey of exactly 128 characters is accepted by each of the nine write tools and is "
            + "the key of the receipt")
    void aKeyOf128CharactersIsAccepted(WriteCase write, McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);
        String key = keyOf(128);

        CallToolResult result = session.call(write.tool(), callOf(session, write, seed).with(KEY, key));

        assertThat(result.isError()).as("%s with a key of 128 characters: %s", write.tool(), result.content()).isFalse();
        assertThat(Ledger.writes(scenario, key)).isOne();
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
                new OpWithKey("save", Calls.Op.save("issue", Fields.issue("p", "t")), null),
                new OpWithKey("transition", Calls.Op.transition("issue", "I-1", 1, "DISPUTED"), null),
                new OpWithKey("record_deliverable", Calls.Op.recordDeliverable("I-1", "rev-1"), null),
                new OpWithKey("record_case", Calls.Op.recordCase("I-1", "seen"), null),
                new OpWithKey("link", Calls.Op.link("issue_task", "I-1", "T-1"), null),
                new OpWithKey("correct", Calls.Op.correct(1), null))
                .flatMap(op -> Stream.of(KEY, "request_id").map(key -> new OpWithKey(op.op(), op.operation(), key)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("operationsWithAKey")
    @DisplayName("OPK-005: an operation of a batch carries no key: an operationKey or a request_id inside operation i is "
            + "refused as unknown at ops[i].<key> and nothing is written")
    void anOperationCarriesNoKey(OpWithKey given, McpSession session, Scenario scenario) {
        String batchKey = Calls.freshKey();
        List<Object> ops = List.of(Args.from(session.mapper(), Calls.Op.recordCase("I-1", "first")),
                Args.from(session.mapper(), given.operation()).with(given.key(), UUID.randomUUID().toString()));
        Args call = Args.from(session.mapper(), new Calls.Batch(List.of(), batchKey, Scenario.AUTHOR)).with("ops", ops);

        ToolError error = session.failed("ledger_batch", call);

        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.issues()).containsExactly(
                new ToolError.Issue("ops[1]." + given.key(), UNKNOWN_ARGUMENT, List.of()));
        assertThat(Ledger.history(session, scenario.projectId())).isEmpty();
        assertThat(Ledger.writes(scenario, batchKey)).isZero();
    }

    @Test
    @DisplayName("OPK-026: an element of ops that is null ([null], [operation, null]) is VALIDATION_FAILED at ops[i] (is required), "
            + "not an internal error, and nothing is written")
    void aNullOperationIsRefusedAtItsPosition(McpSession session, Scenario scenario) {
        Object first = Args.from(session.mapper(), Calls.Op.recordCase("I-1", "first"));
        List<List<Object>> lists = List.of(java.util.Arrays.asList((Object) null),
                java.util.Arrays.asList(first, null), java.util.Arrays.asList(null, first));
        List<Integer> positions = List.of(0, 1, 0);
        for (int i = 0; i < lists.size(); i++) {
            String batchKey = Calls.freshKey();
            Args call = Args.from(session.mapper(), new Calls.Batch(List.of(), batchKey, Scenario.AUTHOR))
                    .with("ops", lists.get(i));

            ToolError error = session.failed("ledger_batch", call);

            assertThat(error.code()).as("ops %s", lists.get(i)).isEqualTo("VALIDATION_FAILED");
            assertThat(error.issues()).containsExactly(
                    new ToolError.Issue("ops[" + positions.get(i) + "]", "is required", List.of()));
            assertThat(Ledger.history(session, scenario.projectId())).isEmpty();
            assertThat(Ledger.writes(scenario, batchKey)).isZero();
        }
    }

    @Test
    @DisplayName("OPK-005: one operationKey for the batch: the same key with the same operations returns the stored "
            + "results and writes once; the same key with other operations is OPERATION_CONFLICT and writes nothing more")
    void theBatchKeyIsReplayed(McpSession session, Scenario scenario) {
        String batchKey = Calls.freshKey();
        List<Calls.Op> ops = List.of(Calls.Op.save("issue", Fields.issue(scenario.projectId(), "first operation")),
                Calls.Op.save("task", Fields.task(scenario.projectId(), "second operation")));

        BatchResults first = session.ok("ledger_batch", new Calls.Batch(ops, batchKey, Scenario.AUTHOR),
                BatchResults.class);
        BatchResults again = session.ok("ledger_batch", new Calls.Batch(ops, batchKey, Scenario.AUTHOR),
                BatchResults.class);
        ToolError other = session.failed("ledger_batch", new Calls.Batch(
                List.of(Calls.Op.save("issue", Fields.issue(scenario.projectId(), "another operation"))), batchKey,
                Scenario.AUTHOR));

        assertThat(first.count()).isEqualTo(2);
        assertThat(again).isEqualTo(first);
        assertThat(other.code()).isEqualTo("OPERATION_CONFLICT");
        assertThat(Ledger.history(session, scenario.projectId())).hasSize(2);
        assertThat(Ledger.writes(scenario, batchKey)).isOne();
        assertThat(Ledger.receiptOperations(scenario, batchKey)).containsExactly("BATCH");
        assertThat(Ledger.receipts(scenario, Scenario.AUTHOR, batchKey)).isOne();
    }

    // ---------------------------------------------------------------------------------------------- OPK-019 (wire)

    private static Calls.Op issueA(Scenario scenario) {
        return Calls.Op.save("issue", Fields.issue(scenario.projectId(), "operation A"));
    }

    private static Calls.Op taskB(Scenario scenario) {
        return Calls.Op.save("task", Fields.task(scenario.projectId(), "operation B"));
    }

    @Test
    @DisplayName("OPK-019 (wire): ledger_batch with a key that was used by [A]: [A] again replays the stored results, [A, B] "
            + "is OPERATION_CONFLICT and B is not written; with a key that was used by [A, B]: [A] and [B, A] are "
            + "OPERATION_CONFLICT; nothing more is written, and the key holds one BATCH receipt")
    void aBatchKeyIsBoundToItsOperations(McpSession session, Scenario scenario) {
        String project = scenario.projectId();
        Calls.Op a = issueA(scenario);
        Calls.Op b = taskB(scenario);
        String single = Calls.freshKey();
        String pair = Calls.freshKey();

        BatchResults first = session.ok("ledger_batch", new Calls.Batch(List.of(a), single, Scenario.AUTHOR),
                BatchResults.class);
        BatchResults again = session.ok("ledger_batch", new Calls.Batch(List.of(a), single, Scenario.AUTHOR),
                BatchResults.class);
        ToolError added = session.failed("ledger_batch", new Calls.Batch(List.of(a, b), single, Scenario.AUTHOR));

        assertThat(first.count()).isOne();
        assertThat(again).isEqualTo(first);
        assertThat(added.code()).isEqualTo("OPERATION_CONFLICT");
        assertThat(Ledger.history(session, project)).as("A once, B not written").hasSize(1);

        session.ok("ledger_batch", new Calls.Batch(List.of(a, b), pair, Scenario.AUTHOR), BatchResults.class);
        ToolError removed = session.failed("ledger_batch", new Calls.Batch(List.of(a), pair, Scenario.AUTHOR));
        ToolError moved = session.failed("ledger_batch", new Calls.Batch(List.of(b, a), pair, Scenario.AUTHOR));

        assertThat(removed.code()).isEqualTo("OPERATION_CONFLICT");
        assertThat(moved.code()).isEqualTo("OPERATION_CONFLICT");
        assertThat(Ledger.history(session, project)).hasSize(3);
        assertThat(Ledger.receiptOperations(scenario, single)).containsExactly("BATCH");
        assertThat(Ledger.receiptOperations(scenario, pair)).containsExactly("BATCH");
    }

    @Test
    @DisplayName("OPK-019 (wire): one operation key is one operation: a ledger_issue_save with the key of a batch, and a "
            + "ledger_batch with the key of a ledger_issue_save, is OPERATION_CONFLICT and writes nothing more")
    void aBatchKeyAndASaveKeyDoNotMeet(McpSession session, Scenario scenario) {
        String project = scenario.projectId();
        String batchKey = Calls.freshKey();
        String saveKey = Calls.freshKey();
        session.ok("ledger_batch", new Calls.Batch(List.of(issueA(scenario)), batchKey, Scenario.AUTHOR),
                BatchResults.class);
        session.ok("ledger_issue_save", Calls.IssueSave.create(project, "saved", Scenario.AUTHOR)
                .withOperationKey(saveKey), WriteOutputs.Saved.class);

        ToolError saveUnderBatchKey = session.failed("ledger_issue_save",
                Calls.IssueSave.create(project, "saved under the key of a batch", Scenario.AUTHOR)
                        .withOperationKey(batchKey));
        ToolError batchUnderSaveKey = session.failed("ledger_batch",
                new Calls.Batch(List.of(issueA(scenario)), saveKey, Scenario.AUTHOR));

        assertThat(saveUnderBatchKey.code()).isEqualTo("OPERATION_CONFLICT");
        assertThat(batchUnderSaveKey.code()).isEqualTo("OPERATION_CONFLICT");
        assertThat(Ledger.history(session, project)).hasSize(2);
        assertThat(Ledger.receiptOperations(scenario, batchKey)).containsExactly("BATCH");
        assertThat(Ledger.receiptOperations(scenario, saveKey)).containsExactly("SAVE");
    }

    // ---------------------------------------------------------------------------------------------- OPK-020 (wire)

    @Test
    @DisplayName("OPK-020 (wire): a second author with a grant who uses the operationKey that the first author used writes its "
            + "own write (no conflict), each holds a receipt under its own author; the same author with other content under "
            + "the key is OPERATION_CONFLICT, and the second author's own replay returns its stored answer")
    void eachAuthorHasItsOwnKeys(McpSession session, Scenario scenario) {
        String project = scenario.projectId();
        String second = scenario.actorWith("second", "issue");
        String key = Calls.freshKey();

        WriteOutputs.Saved byFirst = session.ok("ledger_issue_save",
                Calls.IssueSave.create(project, "first author", Scenario.AUTHOR).withOperationKey(key),
                WriteOutputs.Saved.class);
        WriteOutputs.Saved bySecond = session.ok("ledger_issue_save",
                Calls.IssueSave.create(project, "second author", second).withOperationKey(key),
                WriteOutputs.Saved.class);
        WriteOutputs.Saved replayed = session.ok("ledger_issue_save",
                Calls.IssueSave.create(project, "second author", second).withOperationKey(key),
                WriteOutputs.Saved.class);
        ToolError otherContent = session.failed("ledger_issue_save",
                Calls.IssueSave.create(project, "second author, other content", second).withOperationKey(key));

        assertThat(byFirst.author()).isEqualTo(Scenario.AUTHOR);
        assertThat(bySecond.author()).isEqualTo(second);
        assertThat(bySecond.id()).isNotEqualTo(byFirst.id());
        assertThat(replayed).isEqualTo(bySecond);
        assertThat(otherContent.code()).isEqualTo("OPERATION_CONFLICT");
        assertThat(Ledger.history(session, project)).hasSize(2);
        assertThat(Ledger.receipts(scenario, Scenario.AUTHOR, key)).isOne();
        assertThat(Ledger.receipts(scenario, second, key)).isOne();
        assertThat(Ledger.receipts(scenario, key)).isEqualTo(2);
    }

    @Test
    @DisplayName("OPK-020 (wire): the same holds for the key of a batch: the second author's batch under the first author's "
            + "batch key writes its own operations and holds its own BATCH receipt; other operations under the key are "
            + "OPERATION_CONFLICT for the same author")
    void eachAuthorHasItsOwnBatchKeys(McpSession session, Scenario scenario) {
        String project = scenario.projectId();
        String second = scenario.actorWith("secondbatch", "issue");
        String key = Calls.freshKey();
        List<Calls.Op> ops = List.of(issueA(scenario));

        BatchResults byFirst = session.ok("ledger_batch", new Calls.Batch(ops, key, Scenario.AUTHOR), BatchResults.class);
        BatchResults bySecond = session.ok("ledger_batch", new Calls.Batch(ops, key, second), BatchResults.class);
        ToolError otherOperations = session.failed("ledger_batch",
                new Calls.Batch(List.of(issueA(scenario), issueA(scenario)), key, second));

        assertThat(byFirst.count()).isOne();
        assertThat(bySecond.count()).isOne();
        assertThat(otherOperations.code()).isEqualTo("OPERATION_CONFLICT");
        assertThat(Ledger.history(session, project)).extracting(ReadOutputs.History::authorId)
                .containsExactlyInAnyOrder(Scenario.AUTHOR, second);
        assertThat(Ledger.receipts(scenario, Scenario.AUTHOR, key)).isOne();
        assertThat(Ledger.receipts(scenario, second, key)).isOne();
        assertThat(Ledger.receiptOperations(scenario, key)).containsExactly("BATCH", "BATCH");
    }

    // ---------------------------------------------------------------------------------------------- OPK-022 (server)

    private static final JsonMapper VECTORS = JsonMapper.builder().build();

    /** One list of the contract vectors that the hook and the server share (the tool names of {@code write-tool-vectors.json}). */
    private static List<String> vector(String list) {
        List<String> names = new ArrayList<>();
        VECTORS.readTree(ContractVectors.read(ContractVectors.WRITE_TOOLS)).get(list)
                .forEach(name -> names.add(name.asString()));
        return names;
    }

    static Stream<String> writeAndAttachmentTools() {
        return Stream.concat(vector("write").stream(), vector("attachment").stream());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("writeAndAttachmentTools")
    @DisplayName("OPK-022 (server): the published input schema of a write or attachment tool has operationKey among its "
            + "properties and among its required ones exactly when the tool is in operationKeyArgument of the contract vectors")
    void thePublishedSchemaFollowsTheVectors(String tool, McpSession session) {
        boolean takesKey = vector("operationKeyArgument").contains(tool);

        SchemaView input = SchemaView.of(session.mapper(), session.tool(tool).inputSchema());

        assertThat(input.properties().containsKey(KEY)).as("operationKey is a property of %s", tool).isEqualTo(takesKey);
        assertThat(input.required().contains(KEY)).as("operationKey is required by %s", tool).isEqualTo(takesKey);
    }

    @Test
    @DisplayName("OPK-022 (server): the vectors name the twelve keyed ledger write tools and artifact_begin as the tools "
            + "with an operationKey argument, and ledger_whoami, artifact_chunk, artifact_finish, artifact_status and "
            + "artifact_read as the ones without; the claim list is the twelve without artifact_begin")
    void theVectorListsNameTheTools() {
        List<String> argument = vector("operationKeyArgument");
        List<String> without = writeAndAttachmentTools().filter(tool -> !argument.contains(tool)).toList();

        assertThat(argument).containsExactlyInAnyOrder("ledger_issue_save", "ledger_spec_save", "ledger_task_save",
                "ledger_criterion_save", "ledger_transition", "ledger_record_deliverable", "ledger_record_case",
                "ledger_link", "ledger_correct", "ledger_batch", "ledger_review", "ledger_review_correct",
                "artifact_begin");
        assertThat(without).containsExactlyInAnyOrder("ledger_whoami", "artifact_chunk", "artifact_finish",
                "artifact_status", "artifact_read");
        assertThat(vector("operationKeyClaim")).containsExactlyInAnyOrderElementsOf(
                argument.stream().filter(tool -> !"artifact_begin".equals(tool)).toList());
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
    @MethodSource("org.veridger.mcp.support.WriteCase#keyed")
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

    private static Args batchSaveWithFields(McpSession session, Object fields) {
        List<Object> ops = List.of(Args.of().with("op", "save").with("entity", "issue")
                .with("reason", Scenario.REASON).with("fields", fields));
        return Args.from(session.mapper(), new Calls.Batch(List.of(), Calls.freshKey(), Scenario.AUTHOR))
                .with("ops", ops);
    }

    private static Args batchWith(McpSession session, Scenario scenario, Object ops) {
        return Args.from(session.mapper(), Calls.Batch.of(Scenario.AUTHOR,
                Calls.Op.save("issue", Fields.issue(scenario.projectId(), "as text")))).with("ops", ops);
    }

    static Stream<TextForm> textForms() {
        ToolError.Issue fields = new ToolError.Issue("ops[0].fields", "must be of type SaveFields", List.of());
        ToolError.Issue ops = new ToolError.Issue("ops", "must be of type List", List.of());
        return Stream.of(
                new TextForm("ops[0].fields as a JSON object text", "ledger_batch",
                        (session, scenario) -> batchSaveWithFields(session,
                                session.mapper().writeValueAsString(Fields.issue(scenario.projectId(), "as text"))),
                        fields),
                new TextForm("ops[0].fields as the text null", "ledger_batch",
                        (session, scenario) -> batchSaveWithFields(session, "null"), fields),
                new TextForm("ops as a JSON array text", "ledger_batch", (session, scenario) -> batchWith(session,
                        scenario, session.mapper().writeValueAsString(
                                List.of(Calls.Op.save("issue", Fields.issue(scenario.projectId(), "as text"))))),
                        ops),
                new TextForm("ops as the text null", "ledger_batch",
                        (session, scenario) -> batchWith(session, scenario, "null"), ops));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("textForms")
    @DisplayName("OPK-008: an object or an array sent as a JSON string (ops[0].fields, ops), and the text null for such a "
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
                Calls.IssueSave.create(scenario.projectId(), "plain text", Scenario.AUTHOR))
                .with("reason", "null");

        ToolError error = session.failed("ledger_issue_save", call);

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
        session.ok("ledger_issue_save", Calls.IssueSave.create(project, "single", Scenario.AUTHOR)
                .withOperationKey(single), WriteOutputs.Saved.class);
        session.ok("ledger_batch", new Calls.Batch(List.of(Calls.Op.save("issue", Fields.issue(project, "batched 1")),
                Calls.Op.save("issue", Fields.issue(project, "batched 2"))), batchKey, Scenario.AUTHOR),
                BatchResults.class);

        JsonNode items = session.mapper().valueToTree(session.call("ledger_history", Calls.History.of(project))
                .structuredContent()).path("data").get("items");
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
