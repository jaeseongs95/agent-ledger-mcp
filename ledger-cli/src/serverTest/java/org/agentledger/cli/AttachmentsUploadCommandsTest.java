// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.agentledger.application.attachment.AttachmentProperties;
import org.agentledger.cli.attachment.PreparedFile;
import org.agentledger.cli.attachment.UploadClient;
import org.agentledger.cli.support.CliExtension;
import org.agentledger.cli.support.CliExtension.Cli;
import org.agentledger.cli.support.CliExtension.Outcome;
import org.agentledger.cli.support.EnglishMessages;
import org.agentledger.cli.support.McpTestServer;
import org.agentledger.cli.support.RecordingOutput;
import org.agentledger.domain.value.Sha256;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;

/**
 * ATA-044: {@code attachments-prepare} measures files locally with no server/DB, and {@code attachments-send} streams
 * chunks over HTTP to {@code /mcp} ({@code artifact_status} and {@code artifact_chunk}) using {@code uploadId}
 * capability without session attestation, resuming cleanly from server progress.
 */
@ExtendWith({ OutputCaptureExtension.class, CliExtension.class, EnglishMessages.class })
class AttachmentsUploadCommandsTest {

    private static final String TOKEN = "Upload-Test-Token-0123456789abcdef";

    @TempDir
    Path tmp;

    private final JsonMapper mapper = JsonMapper.builder().build();

    private Outcome run(Cli cli, RecordingOutput output, String... args) {
        return cli.run(tmp.resolve("secrets"), null, output, args);
    }

    @Test
    @DisplayName("ATA-044: attachments-prepare outputs declaration JSON and attachments-send uploads multi-chunk body without session attestation")
    void prepareAndSendMultiChunkResumable(Cli cli, CapturedOutput captured) throws Exception {
        byte[] body = new byte[AttachmentProperties.CHUNK_BYTES + 12345];
        new Random(42).nextBytes(body);
        Path file = tmp.resolve("bundle-artifact.bin");
        Files.write(file, body);

        RecordingOutput prepOut = new RecordingOutput();
        Outcome prep = run(cli, prepOut, "attachments-prepare", file.toString(), "--name=reports/bundle-artifact.bin");
        assertThat(prep.exitCode()).isZero();
        PreparedFile prepared = mapper.readValue(prepOut.info(), PreparedFile.class);
        assertThat(prepared.fileName()).isEqualTo("reports/bundle-artifact.bin");
        assertThat(prepared.size()).isEqualTo(body.length);
        assertThat(prepared.sha256()).isEqualTo(Sha256.ofBytes(body).hex());

        String attachmentId = UUID.randomUUID().toString();
        String uploadId = UUID.randomUUID().toString();
        ByteArrayOutputStream assembled = new ByteArrayOutputStream();
        AtomicLong expectedSeq = new AtomicLong(0);
        AtomicInteger calls = new AtomicInteger(0);

        // Pre-seed chunk 0 to verify attachments-send resumes from server offset/sequence
        assembled.write(body, 0, AttachmentProperties.CHUNK_BYTES);
        expectedSeq.set(1L);

        try (McpTestServer server = startUploadServer(uploadId, attachmentId, body.length, prepared.sha256(),
                assembled, expectedSeq, calls)) {
            RecordingOutput sendOut = new RecordingOutput();
            Outcome sentOutcome = run(cli.withArguments("--ledger.mcp.token=" + TOKEN), sendOut,
                    "attachments-send", file.toString(), "--upload-id=" + uploadId, "--url=" + server.url());

            assertThat(sentOutcome.exitCode()).isZero();
            assertThat(sentOutcome.failure()).isNull();
            UploadClient.Sent sent = mapper.readValue(sendOut.info(), UploadClient.Sent.class);
            assertThat(sent.uploadId()).isEqualTo(uploadId);
            assertThat(sent.attachmentId()).isEqualTo(attachmentId);
            assertThat(sent.seq()).isZero();
            assertThat(sent.received()).isEqualTo(body.length);
            assertThat(calls.get()).isEqualTo(1);
            assertThat(assembled.toByteArray()).isEqualTo(body);
        }
        assertThat(captured.getAll()).doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("ATA-044: attachments-send handles empty files, COMPLETED fast-path, mismatched size, and MCP tool refusals")
    void emptyFileAndErrorHandling(Cli cli) throws Exception {
        Path small = tmp.resolve("small.txt");
        Files.writeString(small, "hello", StandardCharsets.UTF_8);
        String smallSha = Sha256.ofBytes("hello".getBytes(StandardCharsets.UTF_8)).hex();
        String uploadId = UUID.randomUUID().toString();
        String attachmentId = UUID.randomUUID().toString();

        try (McpTestServer server = McpTestServer.start(Files.createDirectories(tmp.resolve("tomcat2")), TOKEN,
                spec -> {
                    Tool statusTool = Tool.builder("artifact_status", Map.of("type", "object")).build();
                    spec.toolCall(statusTool, (exchange, request) -> {
                        String requestedId = String.valueOf(request.arguments().get("uploadId"));
                        if ("missing-id".equals(requestedId)) {
                            Map<String, Object> failed = Map.of("schemaVersion", "1.0.0", "ok", false,
                                    "error", Map.of("code", "ARTIFACT_NOT_FOUND", "message", "Unknown upload"));
                            return CallToolResult.builder().isError(true)
                                    .structuredContent(failed)
                                    .addTextContent("ARTIFACT_NOT_FOUND: Unknown upload")
                                    .build();
                        }
                        if ("wrong-size".equals(requestedId)) {
                            Map<String, Object> envelope = Map.of("schemaVersion", "1.0.0", "ok", true,
                                    "data", Map.of("kind", "upload", "attachmentId", attachmentId, "seq", 0,
                                            "state", "STAGING", "size", 999L, "sha256", smallSha,
                                            "received", 0L, "sequence", 0L));
                            return CallToolResult.builder()
                                    .structuredContent(envelope)
                                    .addTextContent(mapper.writeValueAsString(envelope))
                                    .build();
                        }
                        Map<String, Object> envelope = Map.of("schemaVersion", "1.0.0", "ok", true,
                                "data", Map.of("kind", "upload", "attachmentId", attachmentId, "seq", 0,
                                        "state", "COMPLETED", "size", 5L, "sha256", smallSha,
                                        "received", 5L, "sequence", 1L));
                        return CallToolResult.builder()
                                .structuredContent(envelope)
                                .addTextContent(mapper.writeValueAsString(envelope))
                                .build();
                    });
                })) {
            RecordingOutput completedOut = new RecordingOutput();
            Outcome completed = run(cli.withArguments("--ledger.mcp.token=" + TOKEN), completedOut,
                    "attachments-send", small.toString(), "--upload-id=" + uploadId, "--url=" + server.url());
            assertThat(completed.exitCode()).isZero();
            assertThat(mapper.readValue(completedOut.info(), UploadClient.Sent.class).received()).isEqualTo(5L);

            Outcome notFound = run(cli.withArguments("--ledger.mcp.token=" + TOKEN), new RecordingOutput(),
                    "attachments-send", small.toString(), "--upload-id=missing-id", "--url=" + server.url());
            assertThat(notFound.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
            assertThat(notFound.code()).contains(ErrorCode.ATTACHMENTS_TOOL_REFUSED);

            Outcome mismatch = run(cli.withArguments("--ledger.mcp.token=" + TOKEN), new RecordingOutput(),
                    "attachments-send", small.toString(), "--upload-id=wrong-size", "--url=" + server.url());
            assertThat(mismatch.exitCode()).isEqualTo(ExitStatus.USAGE.code());
            assertThat(mismatch.code()).contains(ErrorCode.ATTACHMENTS_FILE_INVALID);
        }
    }

    private McpTestServer startUploadServer(String uploadId, String attachmentId, long declaredSize,
            String declaredSha, ByteArrayOutputStream assembled, AtomicLong expectedSeq, AtomicInteger chunkCalls)
            throws IOException {
        return McpTestServer.start(Files.createDirectories(tmp.resolve("tomcat1")), TOKEN, spec -> {
            Tool statusTool = Tool.builder("artifact_status", Map.of("type", "object")).build();
            spec.toolCall(statusTool, (exchange, request) -> {
                assertThat(request.arguments()).doesNotContainKey("_hostAttestation");
                assertThat(request.arguments().get("uploadId")).isEqualTo(uploadId);
                Map<String, Object> envelope = Map.of("schemaVersion", "1.0.0", "ok", true,
                        "data", Map.of(
                                "kind", "upload",
                                "attachmentId", attachmentId,
                                "seq", 0,
                                "state", "STAGING",
                                "size", declaredSize,
                                "sha256", declaredSha,
                                "received", (long) assembled.size(),
                                "sequence", expectedSeq.get()));
                return CallToolResult.builder()
                        .structuredContent(envelope)
                        .addTextContent(mapper.writeValueAsString(envelope))
                        .build();
            });
            Tool chunkTool = Tool.builder("artifact_chunk", Map.of("type", "object")).build();
            spec.toolCall(chunkTool, (exchange, request) -> {
                assertThat(request.arguments()).doesNotContainKey("_hostAttestation");
                assertThat(request.arguments().get("uploadId")).isEqualTo(uploadId);
                long seq = ((Number) request.arguments().get("sequence")).longValue();
                assertThat(seq).isEqualTo(expectedSeq.getAndIncrement());
                byte[] chunk = Base64.getDecoder().decode(String.valueOf(request.arguments().get("data")));
                assertThat(Sha256.ofBytes(chunk).hex()).isEqualTo(request.arguments().get("chunkSha256"));
                assembled.writeBytes(chunk);
                chunkCalls.incrementAndGet();
                Map<String, Object> envelope = Map.of("schemaVersion", "1.0.0", "ok", true,
                        "data", Map.of("sequence", seq, "received", (long) assembled.size()));
                return CallToolResult.builder()
                        .structuredContent(envelope)
                        .addTextContent(mapper.writeValueAsString(envelope))
                        .build();
            });
        });
    }
}
