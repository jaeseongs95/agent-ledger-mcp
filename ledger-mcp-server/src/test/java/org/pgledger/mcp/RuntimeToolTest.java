// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import org.pgledger.testing.TestOwners;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.application.admin.CommonCodeService;
import org.pgledger.mcp.error.ToolError;
import org.pgledger.mcp.support.Calls;
import org.pgledger.mcp.support.CodeChanges;
import org.pgledger.mcp.support.McpServerTest;
import org.pgledger.mcp.support.McpSession;
import org.pgledger.mcp.support.ProbeTools;
import org.pgledger.mcp.support.ProbeTools.Pong;
import org.pgledger.mcp.support.SchemaView;
import org.pgledger.mcp.tool.ToolAdapter;
import org.pgledger.mcp.tool.WriteOutputs.Whoami;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.StringUtils;

/**
 * MCC cases for extra test-only {@link ToolAdapter} beans, tools that fail, and a database outage during a code-revision
 * poll. This test class runs in a context of its own so the extra probe tools stay isolated from the main catalogue tests.
 */
@McpServerTest
@Import(RuntimeToolTest.TestProbes.class)
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "ledger.test.context=runtime-tools")
class RuntimeToolTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class TestProbes {

        @Bean
        ToolAdapter workingProbe() {
            return new ProbeTools.Working();
        }

        @Bean
        ToolAdapter defectiveProbe() {
            return new ProbeTools.Defective();
        }

        @Bean
        ToolAdapter violatingProbe() {
            return new ProbeTools.Violating();
        }
    }

    private static final Duration NOTIFICATION = Duration.ofSeconds(5);

    @Test
    @DisplayName("MCC-002: an extra ToolAdapter bean registered in the test context is listed with closed schema and callable")
    void anExtraAdapterBeanIsListedAndCallable(McpSession session) {
        assertThat(session.tools()).extracting(Tool::name).contains("probe_ping");
        assertThat(session.ok("probe_ping", new Calls.Nothing(), Pong.class)).isEqualTo(new Pong("pong"));
        assertThat(SchemaView.of(session.mapper(), session.tool("probe_ping").inputSchema()).closed()).isTrue();
    }

    /** A failing tool, the token of the error and what must not reach the model. */
    private record Failing(String tool, String token, List<String> secrets) {

        @Override
        public String toString() {
            return token;
        }
    }

    static Stream<Failing> failingTools() {
        return Stream.of(
                new Failing("probe_defect", "INTERNAL_ERROR",
                        List.of("boom", "SELECT", "vault", "IllegalStateException")),
                new Failing("probe_integrity", "INTEGRITY_VIOLATION",
                        List.of("insert into", "HISTORY_PK", "DataIntegrityViolationException", "statement")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failingTools")
    @DisplayName("MCC-020: an unexpected exception and an integrity error are INTERNAL_ERROR and "
            + "INTEGRITY_VIOLATION without SQL or class names, and are logged once")
    void anUnexpectedFailureIsConvertedOnce(Failing failing, McpSession session, CapturedOutput output) {
        CallToolResult result = session.call(failing.tool(), new Calls.Nothing());
        ToolError error = session.error(result);

        assertThat(result.isError()).isTrue();
        assertThat(error.code()).isEqualTo(failing.token());
        assertThat(McpSession.text(result)).isEqualTo(failing.token() + ": " + error.message());
        failing.secrets().forEach(secret -> assertThat(McpSession.text(result)).doesNotContain(secret));
        assertThat(StringUtils.countOccurrencesOf(output.getAll(), "a tool call failed with " + failing.token()))
                .isEqualTo(1);
        assertThat(StringUtils.countOccurrencesOf(output.getAll(), failing.secrets().getFirst())).isEqualTo(1);
    }

    @Test
    @DisplayName("MCC-010: the database is gone for a poll, then back: the error is logged, the server keeps "
            + "serving, a later change is still announced")
    void aPollWithoutDatabaseIsSurvived(McpSession session, ApplicationContext context, CapturedOutput output) {
        JdbcClient database = JdbcClient.create(context.getBean(DataSource.class));

        database.sql("ALTER TABLE code_revision RENAME TO code_revision_away").update();
        Awaitility.await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(output.getAll()).contains("a scheduled task failed"));
        Whoami during = session.ok("ledger_whoami", Calls.Whoami.as("claude:nobody"), Whoami.class);
        database.sql("ALTER TABLE code_revision_away RENAME TO code_revision").update();
        context.getBean(CommonCodeService.class).set(CodeChanges.active(CodeChanges.CATEGORY, "AFTER_OUTAGE"), TestOwners.OWNER);
        List<Tool> announced = session.awaitListChange(NOTIFICATION);

        assertThat(during.author()).isEqualTo("claude:nobody");
        assertThat(announced).extracting(Tool::name).contains("ledger_issue_save");
        assertThat(SchemaView.of(session.mapper(), session.tool("ledger_issue_save").inputSchema())
                .at("category").orElseThrow().allowed()).contains("AFTER_OUTAGE");
    }
}
