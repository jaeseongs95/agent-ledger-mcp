package org.pgledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.database.repository.AttachmentRepository;
import org.pgledger.domain.model.Attachment;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.mcp.error.ToolError;
import org.pgledger.mcp.support.Args;
import org.pgledger.mcp.support.Calls;
import org.pgledger.mcp.support.Ledger;
import org.pgledger.mcp.support.McpServerTest;
import org.pgledger.mcp.support.McpSession;
import org.pgledger.mcp.support.Scenario;
import org.pgledger.mcp.support.SchemaView;
import org.pgledger.mcp.support.Seed;
import org.pgledger.mcp.tool.ReadOutputs;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * ATA-012 & ATA-020: optional {@code attachment_id} on the six record tools and four batch operations.
 */
@McpServerTest
class AttachmentRecordWireTest {

    private static final String RECORD_TOOLS_WITH_ID = "the six record tools and the four batch operations";
    private static final String NOT_FOUND = "ARTIFACT_NOT_FOUND";

    private interface Build {
        Args args(McpSession session, Seed seed, String attachmentId, String operationKey);
    }

    private record Case(String name, String tool, String actor, String stranger, String entity, String op,
            Build build) {

        @Override
        public String toString() {
            return name;
        }
    }

    private static Args withAttachment(Args args, String attachmentId) {
        return attachmentId == null ? args : args.with("attachment_id", attachmentId);
    }

    private static Args single(McpSession session, Object call, String attachmentId, String operationKey) {
        return withAttachment(Args.from(session.mapper(), call), attachmentId).with("operationKey", operationKey);
    }

    private static Args batch(McpSession session, Args operation, String attachmentId, String operationKey) {
        return Args.from(session.mapper(), Calls.Batch.of(Scenario.AUTHOR, Calls.Op.recordCase("I", "x")))
                .with("ops", List.of(withAttachment(operation, attachmentId))).with("operationKey", operationKey);
    }

    static Stream<Case> cases() {
        String author = Scenario.AUTHOR;
        String reviewer = Scenario.REVIEWER;
        return Stream.of(
                new Case("ledger_transition", "ledger_transition", author, reviewer, "issue", "TRANSITION",
                        (session, seed, att, key) -> single(session, Calls.Transition.of("issue", seed.issue(), 1,
                                "DISPUTED", author).withEvidence(Scenario.EVIDENCE), att, key)),
                new Case("ledger_review", "ledger_review", reviewer, author, "issue", "TRANSITION",
                        (session, seed, att, key) -> single(session,
                                Calls.Review.of("issue", seed.resolved(), 2, "VERIFIED", reviewer), att, key)),
                new Case("ledger_review_correct", "ledger_review_correct", reviewer, author, "issue", "CORRECT",
                        (session, seed, att, key) -> single(session, Calls.Correct.of(seed.verificationRow(), reviewer),
                                att, key)),
                new Case("ledger_correct", "ledger_correct", author, reviewer, "issue", "CORRECT",
                        (session, seed, att, key) -> single(session, Calls.Correct.of(seed.issueHistoryId(), author),
                                att, key)),
                new Case("ledger_record_deliverable", "ledger_record_deliverable", author, reviewer, "deliverable",
                        "CREATE",
                        (session, seed, att, key) -> single(session,
                                Calls.RecordDeliverable.ofIssue(seed.issue(), "rev-att-1", author), att, key)),
                new Case("ledger_record_case", "ledger_record_case", author, reviewer, "issue_case", "CREATE",
                        (session, seed, att, key) -> single(session, Calls.RecordCase.of(seed.issue(), "seen", author),
                                att, key)),
                new Case("ledger_batch: transition", "ledger_batch", author, reviewer, "issue", "TRANSITION",
                        (session, seed, att, key) -> batch(session, Args.from(session.mapper(),
                                Calls.Op.transition("issue", seed.issue(), 1, "DISPUTED"))
                                .with("evidence_uri", Scenario.EVIDENCE), att, key)),
                new Case("ledger_batch: record_deliverable", "ledger_batch", author, reviewer, "deliverable", "CREATE",
                        (session, seed, att, key) -> batch(session, Args.from(session.mapper(),
                                Calls.Op.recordDeliverable(seed.issue(), "rev-att-2")), att, key)),
                new Case("ledger_batch: record_case", "ledger_batch", author, reviewer, "issue_case", "CREATE",
                        (session, seed, att, key) -> batch(session, Args.from(session.mapper(),
                                Calls.Op.recordCase(seed.issue(), "seen")), att, key)),
                new Case("ledger_batch: correct", "ledger_batch", author, reviewer, "issue", "CORRECT",
                        (session, seed, att, key) -> batch(session, Args.from(session.mapper(),
                                Calls.Op.correct(seed.issueHistoryId())), att, key)));
    }

    private static String attachmentOf(Scenario scenario, String author) {
        String id = UUID.randomUUID().toString();
        Attachment row = new Attachment(id, AuthorId.of(author), Instant.parse("2026-11-05T09:00:00Z"));
        scenario.bean(TransactionTemplate.class).executeWithoutResult(status ->
                scenario.bean(AttachmentRepository.class).insert(row));
        return id;
    }

    private static List<ReadOutputs.History> carrying(McpSession session, Seed seed, String attachmentId) {
        return Ledger.history(session, seed.project()).stream()
                .filter(row -> Objects.equals(row.attachmentId(), attachmentId)).toList();
    }

    private static CallToolResult succeeded(McpSession session, Case c, Args call) {
        CallToolResult result = session.call(c.tool(), call);
        assertThat(result.isError()).as("%s failed: %s", c.name(), result.content()).isFalse();
        return result;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("ATA-012: " + RECORD_TOOLS_WITH_ID + " accept attachment_id of the attesting author; the history read returns it on the row of the operation's entity, and null on the others")
    void roundTrip(Case c, McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);
        String attachment = attachmentOf(scenario, c.actor());
        int before = Ledger.history(session, seed.project()).size();

        succeeded(session, c, c.build().args(session, seed, attachment, UUID.randomUUID().toString()));

        List<ReadOutputs.History> rows = carrying(session, seed, attachment);
        assertThat(rows).as("only the main row of the operation carries the id").hasSize(1);
        assertThat(rows.getFirst().entity()).isEqualTo(c.entity());
        assertThat(rows.getFirst().op()).isEqualTo(c.op());
        List<ReadOutputs.History> all = Ledger.history(session, seed.project());
        assertThat(all.size()).isGreaterThan(before);
        assertThat(all.stream().filter(row -> row.attachmentId() != null)).hasSize(1);
    }

    @Test
    @DisplayName("ATA-012: the history output holds the key attachment_id, null for a row without one")
    void readOutputHoldsTheKey(McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);
        String attachment = attachmentOf(scenario, Scenario.AUTHOR);
        session.ok("ledger_record_case", Args.from(session.mapper(), Calls.RecordCase.of(seed.issue(), "seen",
                Scenario.AUTHOR)).with("attachment_id", attachment), Object.class);

        JsonNode items = session.mapper().valueToTree(session.call("ledger_history",
                Calls.History.of(seed.project())).structuredContent()).path("data").get("items");

        assertThat(items.size()).isPositive();
        for (JsonNode row : items) {
            assertThat(row.has("attachment_id")).as("every row has the key: %s", row).isTrue();
        }
        assertThat(items.get(0).get("attachment_id").isNull()).isTrue();
        assertThat(items.get(items.size() - 1).get("attachment_id").asString()).isEqualTo(attachment);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("ATA-012: an id that does not exist is ARTIFACT_NOT_FOUND and nothing is written, no receipt either")
    void missingAttachment(Case c, McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);
        int before = Ledger.history(session, seed.project()).size();
        String operationKey = UUID.randomUUID().toString();

        ToolError error = session.failed(c.tool(), c.build().args(session, seed, UUID.randomUUID().toString(), operationKey));

        assertThat(error.code()).isEqualTo(NOT_FOUND);
        assertThat(Ledger.history(session, seed.project())).hasSize(before);
        assertThat(Ledger.receipts(scenario, operationKey)).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("ATA-012: the attachment of another author is ARTIFACT_NOT_FOUND and nothing is written")
    void attachmentOfAnotherAuthor(Case c, McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);
        String foreign = attachmentOf(scenario, c.stranger());
        int before = Ledger.history(session, seed.project()).size();
        String operationKey = UUID.randomUUID().toString();

        ToolError error = session.failed(c.tool(), c.build().args(session, seed, foreign, operationKey));

        assertThat(error.code()).isEqualTo(NOT_FOUND);
        assertThat(Ledger.history(session, seed.project())).hasSize(before);
        assertThat(carrying(session, seed, foreign)).isEmpty();
        assertThat(Ledger.receipts(scenario, operationKey)).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("ATA-012: an id that is not a UUID is VALIDATION_FAILED naming attachment_id, and nothing is written")
    void notAUuid(Case c, McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);
        int before = Ledger.history(session, seed.project()).size();

        ToolError error = session.failed(c.tool(), c.build().args(session, seed, "not-a-uuid", UUID.randomUUID().toString()));

        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.issues()).extracting(ToolError.Issue::path).anyMatch(path -> path.endsWith("attachment_id"));
        assertThat(Ledger.history(session, seed.project())).hasSize(before);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("ATA-012: the same operation key with the same attachment returns the stored response; with another attachment, or without one, it is OPERATION_CONFLICT")
    void replay(Case c, McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);
        String attachment = attachmentOf(scenario, c.actor());
        String other = attachmentOf(scenario, c.actor());
        String operationKey = UUID.randomUUID().toString();
        CallToolResult first = succeeded(session, c, c.build().args(session, seed, attachment, operationKey));
        int rows = Ledger.history(session, seed.project()).size();

        CallToolResult again = succeeded(session, c, c.build().args(session, seed, attachment, operationKey));

        assertThat(again.structuredContent()).isEqualTo(first.structuredContent());
        assertThat(Ledger.history(session, seed.project())).as("a replay writes nothing").hasSize(rows);
        assertThat(session.failed(c.tool(), c.build().args(session, seed, other, operationKey)).code())
                .isEqualTo("OPERATION_CONFLICT");
        assertThat(session.failed(c.tool(), c.build().args(session, seed, null, operationKey)).code())
                .isEqualTo("OPERATION_CONFLICT");
        assertThat(carrying(session, seed, other)).isEmpty();
    }

    private static final List<String> TOOLS_WITH_ID = List.of("ledger_transition", "ledger_review",
            "ledger_review_correct", "ledger_correct", "ledger_record_deliverable", "ledger_record_case");

    @Test
    @DisplayName("ATA-020: the six record tools declare an optional string attachment_id with a description; ledger_review keeps evidence_uri required; save and link tools have none")
    void theSchemaDeclaresAttachmentId(McpSession session) {
        for (String tool : TOOLS_WITH_ID) {
            SchemaView schema = SchemaView.of(session.mapper(), session.tool(tool).inputSchema());
            SchemaView property = schema.properties().get("attachment_id");
            assertThat(property).as("attachment_id of %s", tool).isNotNull();
            assertThat(property.type()).isEqualTo("string");
            assertThat(schema.required()).doesNotContain("attachment_id");
            assertThat(property.description()).contains("attachment").doesNotStartWith("param.");
        }
        SchemaView review = SchemaView.of(session.mapper(), session.tool("ledger_review").inputSchema());
        assertThat(review.required()).contains("evidence_uri");
        for (String tool : List.of("ledger_issue_save", "ledger_spec_save", "ledger_task_save", "ledger_criterion_save",
                "ledger_link")) {
            assertThat(SchemaView.of(session.mapper(), session.tool(tool).inputSchema()).properties())
                    .doesNotContainKey("attachment_id");
        }
    }

    @Test
    @DisplayName("ATA-020: the operations transition, record_deliverable, record_case and correct of ledger_batch list attachment_id; save and link do not")
    void theBatchSchemaListsAttachmentIdOnFourOperations(McpSession session) {
        SchemaView batch = SchemaView.of(session.mapper(), session.tool("ledger_batch").inputSchema());

        List<SchemaView> withId = batch.objects().filter(node -> node.properties().containsKey("attachment_id")).toList();

        assertThat(withId).hasSizeGreaterThanOrEqualTo(4);
        assertThat(withId).allSatisfy(node -> assertThat(node.properties()).doesNotContainKeys("fields", "remove"));
        assertThat(withId).anySatisfy(node -> assertThat(node.properties()).containsKey("history_id"));
        assertThat(withId).anySatisfy(node -> assertThat(node.properties()).containsKey("deliverable_ref"));
        assertThat(withId).anySatisfy(node -> assertThat(node.properties()).containsKey("observation"));
        assertThat(withId).anySatisfy(node -> assertThat(node.properties()).containsKey("to"));
    }
}
