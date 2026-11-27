// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli;

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
import org.veridger.application.attachment.AttachmentProperties;
import org.veridger.cli.attachment.PreparedFile;
import org.veridger.cli.attachment.UploadClient;
import org.veridger.cli.support.CliExtension;
import org.veridger.cli.support.CliExtension.Cli;
import org.veridger.cli.support.CliExtension.Outcome;
import org.veridger.cli.support.EnglishMessages;
import org.veridger.cli.support.McpTestServer;
import org.veridger.cli.support.RecordingOutput;
import org.veridger.domain.value.Sha256;
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

    @Test
    @DisplayName("ATA-044: attachments-send uploads 0-byte files, decodes text-only/direct/error payloads, and probes on interruption")
    void zeroByteTextPayloadsErrorsAndInterruptedProbe(Cli cli) throws Exception {
        Path empty = tmp.resolve("empty.bin");
        Files.write(empty, new byte[0]);
        String emptySha = Sha256.ofBytes(new byte[0]).hex();

        Path data = tmp.resolve("data.bin");
        byte[] content = "payload-bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(data, content);
        String dataSha = Sha256.ofBytes(content).hex();

        String attachmentId = UUID.randomUUID().toString();
        AtomicInteger interruptProbes = new AtomicInteger();

        try (McpTestServer server = McpTestServer.start(Files.createDirectories(tmp.resolve("tomcat3")), TOKEN,
                spec -> {
                    Tool statusTool = Tool.builder("artifact_status", Map.of("type", "object")).build();
                    spec.toolCall(statusTool, (exchange, request) -> {
                        String id = String.valueOf(request.arguments().get("uploadId"));
                        if ("empty-direct".equals(id)) {
                            // Direct UploadStatusPayload (no envelope wrapper) in text content only
                            String raw = "{\"kind\":\"upload\",\"attachmentId\":\"" + attachmentId
                                    + "\",\"seq\":1,\"state\":\"STAGING\",\"size\":0,\"sha256\":\"" + emptySha
                                    + "\",\"received\":0,\"sequence\":0}";
                            return CallToolResult.builder().addTextContent(raw).build();
                        }
                        if ("ok-false-status".equals(id)) {
                            return CallToolResult.builder()
                                    .addTextContent("{\"ok\":false,\"error\":{\"code\":\"BAD_STATE\",\"message\":\"expired\"}}")
                                    .build();
                        }
                        if ("malformed-status".equals(id)) {
                            return CallToolResult.builder().addTextContent("{not-json").build();
                        }
                        if ("empty-content".equals(id)) {
                            return CallToolResult.builder().build();
                        }
                        if ("err-direct-json".equals(id)) {
                            return CallToolResult.builder().isError(true)
                                    .addTextContent("{\"code\":\"DIRECT_ERR\",\"message\":\"direct refusal\"}")
                                    .build();
                        }
                        if ("err-colon-text".equals(id)) {
                            return CallToolResult.builder().isError(true)
                                    .addTextContent("COLON_ERR: plain refusal")
                                    .build();
                        }
                        if ("err-plain-text".equals(id)) {
                            return CallToolResult.builder().isError(true)
                                    .addTextContent("no-colon-not-json")
                                    .build();
                        }
                        if ("err-unknown-json".equals(id)) {
                            return CallToolResult.builder().isError(true)
                                    .addTextContent("{}")
                                    .build();
                        }
                        if ("interrupt-stopped".equals(id)) {
                            int n = interruptProbes.incrementAndGet();
                            String raw = "{\"ok\":true,\"data\":{\"kind\":\"upload\",\"attachmentId\":\""
                                    + attachmentId + "\",\"seq\":0,\"state\":\"STAGING\",\"size\":" + content.length
                                    + ",\"sha256\":\"" + dataSha + "\",\"received\":" + (n == 1 ? 0 : 4)
                                    + ",\"sequence\":" + (n == 1 ? 0 : 1) + "}}";
                            return CallToolResult.builder().addTextContent(raw).build();
                        }
                        if ("interrupt-completed".equals(id)) {
                            int n = interruptProbes.incrementAndGet();
                            String st = n == 1 ? "STAGING" : "COMPLETED";
                            long rec = n == 1 ? 0 : content.length;
                            String raw = "{\"ok\":true,\"data\":{\"kind\":\"upload\",\"attachmentId\":\""
                                    + attachmentId + "\",\"seq\":2,\"state\":\"" + st + "\",\"size\":" + content.length
                                    + ",\"sha256\":\"" + dataSha + "\",\"received\":" + rec
                                    + ",\"sequence\":" + (n == 1 ? 0 : 1) + "}}";
                            return CallToolResult.builder().addTextContent(raw).build();
                        }
                        if ("chunk-refused".equals(id)) {
                            String raw = "{\"ok\":true,\"data\":{\"kind\":\"upload\",\"attachmentId\":\""
                                    + attachmentId + "\",\"seq\":0,\"state\":\"STAGING\",\"size\":" + content.length
                                    + ",\"sha256\":\"" + dataSha + "\",\"received\":0,\"sequence\":0}}";
                            return CallToolResult.builder().addTextContent(raw).build();
                        }
                        return CallToolResult.builder().addTextContent("{}").build();
                    });
                    Tool chunkTool = Tool.builder("artifact_chunk", Map.of("type", "object")).build();
                    spec.toolCall(chunkTool, (exchange, request) -> {
                        String id = String.valueOf(request.arguments().get("uploadId"));
                        if ("empty-direct".equals(id)) {
                            return CallToolResult.builder()
                                    .addTextContent("{\"ok\":true,\"data\":{\"sequence\":0,\"received\":0}}")
                                    .build();
                        }
                        if ("chunk-refused".equals(id)) {
                            return CallToolResult.builder()
                                    .addTextContent("{\"ok\":false,\"error\":{\"code\":\"CHUNK_BAD\",\"message\":\"bad hash\"}}")
                                    .build();
                        }
                        // Malformed chunk JSON triggers MCP_FAILED -> sendAndVerify catches and probes status!
                        return CallToolResult.builder().addTextContent("{broken-chunk-json").build();
                    });
                })) {
            Cli authed = cli.withArguments("--ledger.mcp.token=" + TOKEN);

            // 0-byte upload via direct status payload
            RecordingOutput emptyOut = new RecordingOutput();
            Outcome emptyRes = run(authed, emptyOut, "attachments-send", empty.toString(),
                    "--upload-id=empty-direct", "--url=" + server.url());
            assertThat(emptyRes.exitCode()).isZero();
            assertThat(mapper.readValue(emptyOut.info(), UploadClient.Sent.class).seq()).isEqualTo(1);

            // Error decoding & refusal branches
            assertThat(run(authed, new RecordingOutput(), "attachments-send", data.toString(),
                    "--upload-id=ok-false-status", "--url=" + server.url()).code()).contains(ErrorCode.ATTACHMENTS_TOOL_REFUSED);
            assertThat(run(authed, new RecordingOutput(), "attachments-send", data.toString(),
                    "--upload-id=malformed-status", "--url=" + server.url()).code()).contains(ErrorCode.MCP_FAILED);
            assertThat(run(authed, new RecordingOutput(), "attachments-send", data.toString(),
                    "--upload-id=empty-content", "--url=" + server.url()).code()).contains(ErrorCode.MCP_FAILED);
            assertThat(run(authed, new RecordingOutput(), "attachments-send", data.toString(),
                    "--upload-id=err-direct-json", "--url=" + server.url()).code()).contains(ErrorCode.ATTACHMENTS_TOOL_REFUSED);
            assertThat(run(authed, new RecordingOutput(), "attachments-send", data.toString(),
                    "--upload-id=err-colon-text", "--url=" + server.url()).code()).contains(ErrorCode.ATTACHMENTS_TOOL_REFUSED);
            assertThat(run(authed, new RecordingOutput(), "attachments-send", data.toString(),
                    "--upload-id=err-plain-text", "--url=" + server.url()).code()).contains(ErrorCode.MCP_FAILED);
            assertThat(run(authed, new RecordingOutput(), "attachments-send", data.toString(),
                    "--upload-id=err-unknown-json", "--url=" + server.url()).code()).contains(ErrorCode.ATTACHMENTS_TOOL_REFUSED);
            assertThat(run(authed, new RecordingOutput(), "attachments-send", data.toString(),
                    "--upload-id=chunk-refused", "--url=" + server.url()).code()).contains(ErrorCode.ATTACHMENTS_TOOL_REFUSED);

            // Interrupted chunk -> status probe returns Stopped with resume coordinates
            interruptProbes.set(0);
            Outcome stoppedRes = run(authed, new RecordingOutput(), "attachments-send", data.toString(),
                    "--upload-id=interrupt-stopped", "--url=" + server.url());
            assertThat(stoppedRes.exitCode()).isEqualTo(ExitStatus.FAILURE.code());

            // Interrupted chunk -> status probe discovers server already COMPLETED -> returns Sent (exit 0)
            interruptProbes.set(0);
            RecordingOutput recoveredOut = new RecordingOutput();
            Outcome recoveredRes = run(authed, recoveredOut, "attachments-send", data.toString(),
                    "--upload-id=interrupt-completed", "--url=" + server.url());
            assertThat(recoveredRes.exitCode()).isZero();
            assertThat(mapper.readValue(recoveredOut.info(), UploadClient.Sent.class).seq()).isEqualTo(2);
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
