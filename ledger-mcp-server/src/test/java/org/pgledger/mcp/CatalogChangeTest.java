package org.pgledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.application.admin.CommonCodeService;
import org.pgledger.mcp.support.Calls;
import org.pgledger.mcp.support.CodeChanges;
import org.pgledger.mcp.support.CodeListing;
import org.pgledger.mcp.support.McpServerTest;
import org.pgledger.mcp.support.McpSession;
import org.pgledger.mcp.support.Parallel;
import org.pgledger.mcp.support.Parallel.Outcome;
import org.pgledger.mcp.support.Scenario;
import org.pgledger.mcp.support.SchemaView;
import org.pgledger.mcp.support.ToolNames;
import org.pgledger.mcp.tool.ReadOutputs;
import org.pgledger.mcp.tool.WriteOutputs;
import org.pgledger.testing.TestOwners;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MCC cases about dynamic common-code updates reflected on {@code ledger_issue_save}, {@code ledger_record_deliverable},
 * and {@code ledger_link} via {@code notifications/tools/list_changed}.
 */
@McpServerTest
@TestPropertySource(properties = "ledger.test.context=catalog-changes")
class CatalogChangeTest {

    private static final Duration NOTIFICATION = Duration.ofSeconds(5);
    private static final Duration THREE_POLLS = Duration.ofMillis(1500);

    private static SchemaView issueSaveField(McpSession session, String path) {
        return SchemaView.of(session.mapper(), session.tool("ledger_issue_save").inputSchema()).at(path).orElseThrow();
    }

    private static CommonCodeService codes(ApplicationContext context) {
        return context.getBean(CommonCodeService.class);
    }

    @Test
    @DisplayName("MCC-003: a category added by another process is announced and shown in the ledger_issue_save schema without a restart")
    void aNewCategoryIsAnnounced(McpSession session, ApplicationContext context) {
        codes(context).set(CodeChanges.active(CodeChanges.CATEGORY, "UI_UX"), TestOwners.OWNER);

        List<Tool> announced = session.awaitListChange(NOTIFICATION);

        assertThat(announced).extracting(Tool::name).containsExactlyInAnyOrderElementsOf(ToolNames.all());
        assertThat(issueSaveField(session, "category").allowed()).contains("UI_UX");
        assertThat(issueSaveField(session, "category").description()).contains("UI_UX name UI_UX (Name UI_UX)");
    }

    @Test
    @DisplayName("MCC-004: a code set INACTIVE is announced and removed from enumeration and description, and shown again when reactivated")
    void aRetiredCodeLeavesAndComesBack(McpSession session, ApplicationContext context) {
        codes(context).set(CodeChanges.active(CodeChanges.CATEGORY, "COMES_AND_GOES"), TestOwners.OWNER);
        session.awaitListChange(NOTIFICATION);
        SchemaView shown = issueSaveField(session, "category");

        codes(context).set(CodeChanges.inactive(CodeChanges.CATEGORY, "COMES_AND_GOES"), TestOwners.OWNER);
        session.awaitListChange(NOTIFICATION);
        SchemaView retired = issueSaveField(session, "category");

        codes(context).set(CodeChanges.active(CodeChanges.CATEGORY, "COMES_AND_GOES"), TestOwners.OWNER);
        session.awaitListChange(NOTIFICATION);
        SchemaView reactivated = issueSaveField(session, "category");

        assertThat(shown.allowed()).contains("COMES_AND_GOES");
        assertThat(retired.allowed()).doesNotContain("COMES_AND_GOES");
        assertThat(retired.description()).doesNotContain("COMES_AND_GOES");
        assertThat(reactivated.allowed()).contains("COMES_AND_GOES");
        assertThat(reactivated.description()).contains("COMES_AND_GOES");
    }

    @Test
    @DisplayName("MCC-005: a relation kind, a severity, and a deliverable kind added by another process show in the live tool schemas")
    void aRelationKindAndASeverityAreShown(McpSession session, ApplicationContext context) {
        codes(context).set(CodeChanges.active(CodeChanges.RELATION, "BLOCKS_NEW"), TestOwners.OWNER);
        session.awaitListChange(NOTIFICATION);
        List<String> kinds = SchemaView.of(session.mapper(), session.tool("ledger_link").inputSchema())
                .at("kind").orElseThrow().allowed();

        codes(context).set(CodeChanges.active(CodeChanges.SEVERITY, "P7"), TestOwners.OWNER);
        session.awaitListChange(NOTIFICATION);
        SchemaView severity = issueSaveField(session, "severity");

        codes(context).set(CodeChanges.active(CodeChanges.DELIVERABLE, "AUDIO_CUT"), TestOwners.OWNER);
        session.awaitListChange(NOTIFICATION);
        SchemaView deliverableKind = SchemaView.of(session.mapper(),
                session.tool("ledger_record_deliverable").inputSchema()).at("kind").orElseThrow();

        assertThat(kinds).contains("issue_task", "issue_spec", "spec_task", "criterion_task", "criterion_issue",
                "criterion_spec", "BLOCKS_NEW");
        assertThat(severity.allowed()).contains("P1", "P2", "P3", "P7");
        assertThat(severity.description()).contains("P7 name P7 (Name P7)");
        assertThat(deliverableKind.allowed()).contains("code_change", "document", "media", "AUDIO_CUT");
    }

    @Test
    @DisplayName("MCC-006: without a change there is no notification for three polls, and a write does not cause one")
    void noChangeNoNotification(McpSession session, Scenario scenario) {
        session.drainListChanges(THREE_POLLS);
        session.awaitNoListChange(THREE_POLLS);

        session.ok("ledger_issue_save", Calls.IssueSave.create(scenario.projectId(), "a write", Scenario.AUTHOR),
                WriteOutputs.Saved.class);

        session.awaitNoListChange(THREE_POLLS);
    }

    @Test
    @DisplayName("MCC-007: three open sessions are all notified, a fourth opened afterwards sees the new schema")
    void everySessionIsNotified(McpSession first, McpSession second, McpSession third, ApplicationContext context) {
        codes(context).set(CodeChanges.active(CodeChanges.CATEGORY, "FOR_THREE"), TestOwners.OWNER);

        List<List<Tool>> announced = Stream.of(first, second, third)
                .map(session -> session.awaitListChange(NOTIFICATION)).toList();

        try (McpSession fourth = McpSession.open(context)) {
            assertThat(issueSaveField(fourth, "category").allowed()).contains("FOR_THREE");
        }
        assertThat(announced).hasSize(3).allSatisfy(tools -> assertThat(tools).hasSize(33));
    }

    @Test
    @DisplayName("MCC-008: a session with the old schema writes the new code, the service reads the database")
    void theOldSchemaDoesNotLimitTheWrite(McpSession session, Scenario scenario, ApplicationContext context) {
        List<String> before = issueSaveField(session, "category").allowed();
        codes(context).set(CodeChanges.active(CodeChanges.CATEGORY, "NOT_LISTED_YET"), TestOwners.OWNER);

        WriteOutputs.Saved saved = session.ok("ledger_issue_save",
                Calls.IssueSave.create(scenario.projectId(), "new code", Scenario.AUTHOR)
                        .withCategory("NOT_LISTED_YET"),
                WriteOutputs.Saved.class);

        assertThat(before).doesNotContain("NOT_LISTED_YET");
        assertThat(session.ok("ledger_issue", new Calls.IdOnly(saved.id()), ReadOutputs.Issue.class).category())
                .isEqualTo("NOT_LISTED_YET");
    }

    @Test
    @DisplayName("MCC-009: a code change whose transaction rolls back is not announced")
    void aRolledBackChangeIsNotAnnounced(McpSession session, ApplicationContext context) {
        TransactionTemplate transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

        transaction.executeWithoutResult(status -> {
            codes(context).set(CodeChanges.active(CodeChanges.CATEGORY, "NEVER_COMMITTED"), TestOwners.OWNER);
            status.setRollbackOnly();
        });

        session.awaitNoListChange(THREE_POLLS);
        assertThat(issueSaveField(session, "category").allowed()).doesNotContain("NEVER_COMMITTED");
    }

    @Test
    @DisplayName("MCC-011: tools/list called 200 times while the catalog is rebuilt repeatedly is always a full list")
    void theListIsNeverPartial(McpSession lister, McpSession watcher, ApplicationContext context) {
        Callable<List<Integer>> listing = () -> IntStream.range(0, 200)
                .mapToObj(round -> lister.tools().size()).toList();
        List<CommonCodeService.CodeCommand> flips = List.of(CodeChanges.active(CodeChanges.CATEGORY, "FLIP"),
                CodeChanges.inactive(CodeChanges.CATEGORY, "FLIP"));
        Callable<List<Integer>> changing = () -> IntStream.range(0, 12).mapToObj(round -> {
            codes(context).set(flips.get(round % flips.size()), TestOwners.OWNER);
            return watcher.awaitListChange(NOTIFICATION).size();
        }).toList();

        List<Outcome<List<Integer>>> outcomes = Parallel.all(List.of(listing, changing));

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(outcomes.get(0).value()).hasSize(200).containsOnly(33);
        assertThat(outcomes.get(1).value()).hasSize(12).containsOnly(33);
    }

    @Test
    @DisplayName("MCC-012: codes with quotes, a newline, a backslash and a template marker are listed verbatim, the inactive severity is omitted")
    void specialCharactersArePreserved(McpSession session, ApplicationContext context) {
        codes(context).set(CodeChanges.withTexts(CodeChanges.CATEGORY, "QUOTED", "따옴표 \"quoted\"",
                "say \"hi\"", "line one\nline two", "back\\slash"), TestOwners.OWNER);
        codes(context).set(CodeChanges.withTexts(CodeChanges.CATEGORY, "TEMPLATE", "@CODES:ISSUE_CATEGORY@",
                "@CODES:ISSUE_SEVERITY@", "@CODES:ISSUE_CATEGORY@", "@CODES:ISSUE_CATEGORY@"), TestOwners.OWNER);
        session.awaitListChange(NOTIFICATION);
        SchemaView category = issueSaveField(session, "category");
        SchemaView severity = issueSaveField(session, "severity");
        List<ReadOutputs.CodeRow> active = CodeListing.active(session, CodeChanges.CATEGORY);

        assertThat(active).extracting(ReadOutputs.CodeRow::code).contains("QUOTED", "TEMPLATE");
        assertThat(category.description()).contains(active.stream().map(CodeListing::line).toList()
                .toArray(new String[0]));
        assertThat(category.description()).contains("QUOTED 따옴표 \"quoted\" (say \"hi\"): line one\nline "
                + "two / back\\slash");
        assertThat(category.description()).contains("TEMPLATE @CODES:ISSUE_CATEGORY@ (@CODES:ISSUE_SEVERITY@): "
                + "@CODES:ISSUE_CATEGORY@ / @CODES:ISSUE_CATEGORY@");
        assertThat(severity.allowed()).doesNotContain("UNRATED");
        assertThat(severity.description()).doesNotContain("UNRATED");
    }
}
