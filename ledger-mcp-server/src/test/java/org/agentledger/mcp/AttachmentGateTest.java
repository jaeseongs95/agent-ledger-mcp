// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.agentledger.application.admin.SessionKeyService;
import org.agentledger.mcp.error.ToolError;
import org.agentledger.mcp.support.AttachmentCalls.Begin;
import org.agentledger.mcp.support.AttachmentCalls.Chunk;
import org.agentledger.mcp.support.AttachmentCalls.Finish;
import org.agentledger.mcp.support.AttachmentCalls.Read;
import org.agentledger.mcp.support.AttachmentCalls.Status;
import org.agentledger.mcp.support.McpServerTest;
import org.agentledger.mcp.support.McpSession;
import org.agentledger.mcp.support.McpTestSettings;
import org.agentledger.mcp.support.Scenario;
import org.agentledger.testing.TestOwners;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * ATA-018: the five attachment tools go through the write gate of the ledger like the write tools. A call without an attestation, a
 * call signed with a key that the owner closed, and a call of an author without a live grant are each refused with the token of the
 * gate, and nothing is written: no row of the uploads or the attachments and no file in the attachment directory.
 */
@McpServerTest
class AttachmentGateTest {

    private static final String ID = "00000000-0000-0000-0000-000000000001";
    private static final byte[] BODY = "a body that is never stored".getBytes(StandardCharsets.UTF_8);

    /** The call of each tool, as a function of the attestation of the call (null leaves it out). */
    private static final Map<String, Function<String, Object>> CALLS = Map.of(
            "artifact_begin", author -> Begin.of("gate-key", "a.txt", "text/plain", BODY, author),
            "artifact_chunk", author -> Chunk.of(ID, 0, BODY, author),
            "artifact_finish", author -> new Finish(ID, author),
            "artifact_status", author -> Status.ofUpload(ID, author),
            "artifact_read", author -> Read.of(ID, 0L, 10L, author));

    private static List<String> hostRequiredTools() {
        return List.of("artifact_begin", "artifact_finish", "artifact_read");
    }

    private static long rows(DataSource dataSource) {
        return JdbcClient.create(dataSource)
                .sql("SELECT (SELECT COUNT(*) FROM upload_staging) + (SELECT COUNT(*) FROM attachment)")
                .query(Long.class).single();
    }

    private static List<Path> files(Environment environment) {
        try (Stream<Path> walk = Files.walk(Path.of(environment.getRequiredProperty(
                McpTestSettings.ATTACHMENTS_DIRECTORY)))) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** Runs the call, which the gate has to refuse with the code, and checks that it left no row and no file. */
    private static void refused(McpSession session, DataSource dataSource, Environment environment, String tool,
            String attestation, boolean signed, String code) {
        long rowsBefore = rows(dataSource);
        List<Path> filesBefore = files(environment);
        Object call = CALLS.get(tool).apply(attestation);

        CallToolResult result = signed ? session.call(tool, call) : session.callUnsigned(tool, call);

        assertThat(result.isError()).as("%s must be refused with %s", tool, code).isTrue();
        ToolError error = session.error(result);
        assertThat(error.code()).isEqualTo(code);
        assertThat(rows(dataSource)).as("rows after %s", tool).isEqualTo(rowsBefore);
        assertThat(files(environment)).as("files after %s", tool).isEqualTo(filesBefore);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hostRequiredTools")
    @DisplayName("ATA-018: without a host attestation the session-bound tools are HOST_ATTESTATION_REQUIRED; no row, no file")
    void noAttestation(String tool, McpSession session, Scenario scenario, @Autowired DataSource dataSource,
            @Autowired Environment environment) {
        refused(session, dataSource, environment, tool, null, false, "HOST_ATTESTATION_REQUIRED");
    }

    @Test
    @DisplayName("ATA-018: without a host attestation, artifact_status by attachmentId is HOST_ATTESTATION_REQUIRED while uploadId capability tools refuse unknown ids")
    void uploadCapabilityWithoutAttestation(McpSession session, Scenario scenario, @Autowired DataSource dataSource,
            @Autowired Environment environment) {
        long rowsBefore = rows(dataSource);
        List<Path> filesBefore = files(environment);

        CallToolResult attStatus = session.callUnsigned("artifact_status", Status.ofAttachment(ID, null));
        assertThat(attStatus.isError()).isTrue();
        assertThat(session.error(attStatus).code()).isEqualTo("HOST_ATTESTATION_REQUIRED");

        CallToolResult uploadStatus = session.callUnsigned("artifact_status", Status.ofUpload(ID, null));
        assertThat(uploadStatus.isError()).isTrue();
        assertThat(session.error(uploadStatus).code()).isEqualTo("ARTIFACT_NOT_FOUND");

        CallToolResult chunk = session.callUnsigned("artifact_chunk", Chunk.of(ID, 0, BODY, null));
        assertThat(chunk.isError()).isTrue();
        assertThat(session.error(chunk).code()).isEqualTo("ARTIFACT_NOT_FOUND");

        assertThat(rows(dataSource)).isEqualTo(rowsBefore);
        assertThat(files(environment)).isEqualTo(filesBefore);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.agentledger.mcp.support.ToolNames#attachment")
    @DisplayName("ATA-018: signed with the key that the owner closed, the tool is ATTESTATION_SIGNATURE_INVALID; no row, no file")
    void closedKey(String tool, McpSession session, Scenario scenario, @Autowired SessionKeyService owner,
            @Autowired DataSource dataSource, @Autowired Environment environment) {
        String author = scenario.actorWith("ata18c", "issue");
        assertThat(owner.revoke(author, TestOwners.OWNER)).isTrue();

        refused(session, dataSource, environment, tool, author, true, "ATTESTATION_SIGNATURE_INVALID");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.agentledger.mcp.support.ToolNames#attachment")
    @DisplayName("ATA-018: an author with a live key and no live grant gets SESSION_NOT_GRANTED; no row, no file")
    void noLiveGrant(String tool, McpSession session, Scenario scenario, @Autowired DataSource dataSource,
            @Autowired Environment environment) {
        String author = "claude:nogrant" + UUID.randomUUID().toString().substring(0, 8);

        refused(session, dataSource, environment, tool, author, true, "SESSION_NOT_GRANTED");
    }
}
