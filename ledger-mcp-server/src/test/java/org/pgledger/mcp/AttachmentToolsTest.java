package org.pgledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.application.attachment.AttachmentProperties;
import org.pgledger.mcp.config.LedgerMcpProperties;
import org.pgledger.mcp.error.ToolError;
import org.pgledger.mcp.support.Args;
import org.pgledger.mcp.support.AttachmentCalls;
import org.pgledger.mcp.support.AttachmentCalls.Begin;
import org.pgledger.mcp.support.AttachmentCalls.BeginFile;
import org.pgledger.mcp.support.AttachmentCalls.Chunk;
import org.pgledger.mcp.support.AttachmentCalls.Finish;
import org.pgledger.mcp.support.AttachmentCalls.Read;
import org.pgledger.mcp.support.AttachmentCalls.Status;
import org.pgledger.mcp.support.McpServerTest;
import org.pgledger.mcp.support.McpSession;
import org.pgledger.mcp.support.McpTestSettings;
import org.pgledger.mcp.support.Scenario;
import org.pgledger.mcp.tool.AttachmentOutputs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import tools.jackson.core.type.TypeReference;

/**
 * ATA-001, ATA-032, and bundle round-trip tests through the real MCP server and tools.
 */
@McpServerTest
class AttachmentToolsTest {

    private static final int CHUNK = AttachmentProperties.CHUNK_BYTES;
    private static final int FULL_CHUNK_TEXT = 349_528;

    private static byte[] textBody(int length) {
        StringBuilder text = new StringBuilder(length + 64);
        for (int line = 0; text.length() < length; line++) {
            text.append("line ").append(line).append(": evidence of the review\n");
        }
        return text.substring(0, length).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] slice(byte[] body, int number) {
        int from = number * CHUNK;
        return Arrays.copyOfRange(body, from, Math.min(body.length, from + CHUNK));
    }

    private static String ascii(byte[] bytes) {
        return StandardCharsets.ISO_8859_1.decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static String upload(McpSession session, String author, String mediaType, byte[] body) {
        AttachmentOutputs.Begun begun = session.ok("artifact-begin",
                Begin.of(UUID.randomUUID().toString(), "evidence.bin", mediaType, body, author),
                AttachmentOutputs.Begun.class);
        String uploadId = begun.files().getFirst().uploadId();
        for (int number = 0; number * CHUNK < body.length; number++) {
            session.ok("artifact-chunk", Chunk.of(uploadId, number, slice(body, number), author),
                    AttachmentOutputs.Received.class);
        }
        return session.ok("artifact-finish", new Finish(begun.attachmentId(), author),
                AttachmentOutputs.Finished.class).attachmentId();
    }

    private static AttachmentOutputs.Page page(McpSession session, String author, String attachmentId, long offset) {
        return session.ok("artifact-read", Read.of(attachmentId, offset, CHUNK, author),
                AttachmentOutputs.Page.class);
    }

    private static Set<Path> filesOf(Path directory) {
        try (Stream<Path> walk = Files.walk(directory)) {
            return new HashSet<>(walk.filter(Files::isRegularFile).toList());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @Test
    @DisplayName("ATA-001: begin, three chunks, finish, status and read through the server")
    void aBodyOfThreeChunksIsUploadedAndReadBack(McpSession session, Scenario scenario, @Autowired Clock clock,
            @Autowired Environment environment) {
        String author = scenario.actorWith("ata1", "defect");
        byte[] body = textBody(600 * 1024);
        Path directory = Path.of(environment.getRequiredProperty(McpTestSettings.ATTACHMENTS_DIRECTORY));
        Set<Path> before = filesOf(directory);
        Begin begin = Begin.of(UUID.randomUUID().toString(), "review.md", "text/markdown", body, author);

        AttachmentOutputs.Begun begun = session.ok("artifact-begin", begin, AttachmentOutputs.Begun.class);
        assertThat(begun.maxChunkBytes()).isEqualTo(CHUNK);
        assertThat(begun.expiresAt()).isAfter(clock.instant());
        assertThat(begun.files()).hasSize(1);
        String uploadId = begun.files().getFirst().uploadId();
        // same operationKey with same files returns the same attachmentId and uploadIds
        assertThat(session.ok("artifact-begin", begin, AttachmentOutputs.Begun.class)).isEqualTo(begun);

        long received = 0;
        for (int number = 0; number < 3; number++) {
            byte[] bytes = slice(body, number);
            received += bytes.length;
            assertThat(session.ok("artifact-chunk", Chunk.of(uploadId, number, bytes, author),
                    AttachmentOutputs.Received.class)).isEqualTo(new AttachmentOutputs.Received(number, received));
        }
        assertThat(session.ok("artifact-chunk", Chunk.of(uploadId, 2, slice(body, 2), author),
                AttachmentOutputs.Received.class)).isEqualTo(new AttachmentOutputs.Received(2, received));
        assertThat(session.failed("artifact-chunk", Chunk.of(uploadId, 0, slice(body, 0), author)).code())
                .isEqualTo("ARTIFACT_CHUNK_CONFLICT");
        Chunk wrong = new Chunk(uploadId, 3L, Chunk.of(uploadId, 3, new byte[] {1}, author).data(),
                AttachmentCalls.sha256(new byte[] {2}), author);
        assertThat(session.failed("artifact-chunk", wrong).code()).isEqualTo("CHUNK_HASH_MISMATCH");

        AttachmentOutputs.Status open = session.ok("artifact-status", Status.ofUpload(uploadId, author),
                AttachmentOutputs.Status.class);
        assertThat(open.kind()).isEqualTo("upload");
        assertThat(open.uploadId()).isEqualTo(uploadId);
        assertThat(open.attachmentId()).isEqualTo(begun.attachmentId());
        assertThat(open.seq()).isZero();
        assertThat(open.state()).isEqualTo("STAGING");
        assertThat(open.received()).isEqualTo(body.length);
        assertThat(open.sequence()).isEqualTo(3L);
        assertThat(open.expiresAt()).isNotNull();

        AttachmentOutputs.Finished finished = session.ok("artifact-finish", new Finish(begun.attachmentId(), author),
                AttachmentOutputs.Finished.class);
        assertThat(finished.attachmentId()).isEqualTo(begun.attachmentId());
        assertThat(finished.files()).containsExactly(
                new AttachmentOutputs.KeptFile(0, "review.md", "text/markdown", body.length,
                        AttachmentCalls.sha256(body)));
        assertThat(session.ok("artifact-finish", new Finish(begun.attachmentId(), author),
                AttachmentOutputs.Finished.class)).isEqualTo(finished);

        CallToolResult statusResult = session.call("artifact-status",
                Status.ofAttachment(finished.attachmentId(), author));
        assertThat(statusResult.isError()).isFalse();
        Map<String, Object> wire = session.mapper().convertValue(statusResult.structuredContent(),
                new TypeReference<Map<String, Object>>() {
                });
        assertThat(wire.keySet()).containsExactlyInAnyOrder("kind", "attachmentId", "state", "files");
        AttachmentOutputs.Status kept = session.read(statusResult, AttachmentOutputs.Status.class);
        assertThat(kept.kind()).isEqualTo("attachment");
        assertThat(kept.state()).isEqualTo("COMPLETED");
        assertThat(kept.files()).isEqualTo(finished.files());

        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        long offset = 0;
        int pages = 0;
        while (offset < body.length) {
            AttachmentOutputs.Page page = page(session, author, finished.attachmentId(), offset);
            byte[] bytes = java.util.Base64.getDecoder().decode(page.data());
            assertThat(page.seq()).isZero();
            assertThat(page.offsetBytes()).isEqualTo(offset);
            assertThat(page.totalBytes()).isEqualTo(body.length);
            assertThat(page.size()).isEqualTo(bytes.length);
            assertThat(page.mediaType()).isEqualTo("text/markdown");
            assertThat(page.fileName()).isEqualTo("review.md");
            assertThat(page.text()).isEqualTo(ascii(bytes));
            joined.writeBytes(bytes);
            offset += bytes.length;
            pages++;
        }
        assertThat(pages).isEqualTo(3);
        assertThat(joined.toByteArray()).isEqualTo(body);
        assertThat(AttachmentCalls.sha256(joined.toByteArray())).isEqualTo(AttachmentCalls.sha256(body));

        Set<Path> added = filesOf(directory);
        added.removeAll(before);
        assertThat(added).hasSize(1).allSatisfy(file -> {
            assertThat(file.getFileName().toString()).doesNotContain(".").doesNotContain("review");
            assertThat(ascii(read(file))).doesNotContain("evidence of the review");
        });
    }

    @Test
    @DisplayName("ATA-044: multi-file bundle via MCP tools supports unsigned uploadId-capability chunks/status and reads both files by seq")
    void multiFileBundleAndUploadIdCapabilityRoundTrip(McpSession session, Scenario scenario) {
        String author = scenario.actorWith("ata44", "defect");
        byte[] doc = "bundle primary review\n".getBytes(StandardCharsets.UTF_8);
        byte[] raw = new byte[] {1, 2, 3, 4, 5};
        Begin begin = Begin.ofBundle(UUID.randomUUID().toString(), List.of(
                BeginFile.of("review.md", "text/markdown", doc),
                BeginFile.of("trace.bin", "application/octet-stream", raw)), author);

        AttachmentOutputs.Begun begun = session.ok("artifact-begin", begin, AttachmentOutputs.Begun.class);
        assertThat(begun.files()).hasSize(2);
        String u0 = begun.files().get(0).uploadId();
        String u1 = begun.files().get(1).uploadId();

        // Call artifact-status and artifact-chunk WITHOUT _hostAttestation (simulating external attachments-send CLI)
        AttachmentOutputs.Status s0 = session.read(session.callUnsigned("artifact-status",
                Status.ofUpload(u0, null)), AttachmentOutputs.Status.class);
        assertThat(s0.kind()).isEqualTo("upload");
        assertThat(s0.seq()).isZero();
        session.callUnsigned("artifact-chunk", Chunk.of(u0, 0, doc, null));
        session.callUnsigned("artifact-chunk", Chunk.of(u1, 0, raw, null));

        AttachmentOutputs.Finished finished = session.ok("artifact-finish", new Finish(begun.attachmentId(), author),
                AttachmentOutputs.Finished.class);
        assertThat(finished.files()).hasSize(2);

        AttachmentOutputs.Page p0 = session.ok("artifact-read",
                new Read(begun.attachmentId(), null, 0L, 100L, author), AttachmentOutputs.Page.class);
        AttachmentOutputs.Page p1 = session.ok("artifact-read",
                new Read(begun.attachmentId(), 1, 0L, 100L, author), AttachmentOutputs.Page.class);
        assertThat(p0.fileName()).isEqualTo("review.md");
        assertThat(p0.text()).isEqualTo("bundle primary review\n");
        assertThat(p1.fileName()).isEqualTo("trace.bin");
        assertThat(p1.seq()).isEqualTo(1);
        assertThat(java.util.Base64.getDecoder().decode(p1.data())).isEqualTo(raw);
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @Test
    @DisplayName("ATA-001: the page is also given as text for a text or JSON type whose bytes are valid UTF-8, and as null otherwise")
    void thePageIsTextOnlyForTextTypesWithValidBytes(McpSession session, Scenario scenario) {
        String author = scenario.actorWith("ata1t", "defect");
        byte[] json = "{\"verdict\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
        byte[] cut = {(byte) 0xC3, 0x28};

        AttachmentOutputs.Page asJson = page(session, author, upload(session, author, "application/json", json), 0);
        AttachmentOutputs.Page asBinary = page(session, author,
                upload(session, author, "application/octet-stream", json), 0);
        AttachmentOutputs.Page notUtf8 = page(session, author, upload(session, author, "text/plain", cut), 0);

        assertThat(asJson.text()).isEqualTo("{\"verdict\":\"ok\"}");
        assertThat(asBinary.text()).isNull();
        assertThat(asBinary.data()).isEqualTo(asJson.data());
        assertThat(notUtf8.text()).isNull();
        assertThat(notUtf8.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("ATA-001: a full chunk (262144 bytes, 349528 characters of base64) is inside the default request limit of the server")
    void aFullChunkFitsTheRequestLimit(McpSession session, Scenario scenario, @Autowired LedgerMcpProperties properties) {
        String author = scenario.actorWith("ata1l", "defect");
        byte[] body = textBody(CHUNK);
        Chunk full = Chunk.of("00000000-0000-0000-0000-000000000000", 0, body, author);

        assertThat(full.data()).hasSize(FULL_CHUNK_TEXT);
        assertThat(properties.maxRequestSize().toBytes()).isGreaterThan(FULL_CHUNK_TEXT + 4096L);
        String attachmentId = upload(session, author, "text/plain", body);
        assertThat(page(session, author, attachmentId, 0).size()).isEqualTo(CHUNK);
    }

    @Test
    @DisplayName("ATA-032: begin without an operationKey, and with an empty one, is VALIDATION_FAILED at the path operationKey")
    void beginNeedsAnOperationKey(McpSession session, Scenario scenario, @Autowired AttachmentProperties properties) {
        String author = scenario.actorWith("ata32", "defect");
        byte[] body = textBody(100);
        Begin begin = Begin.of(null, "a.txt", "text/plain", body, author);

        for (int round = 0; round <= properties.maxOpenPerOwner(); round++) {
            ToolError missing = session.failed("artifact-begin", begin);
            assertThat(missing.code()).isEqualTo("VALIDATION_FAILED");
            assertThat(missing.issues()).extracting(ToolError.Issue::path).containsExactly("operationKey");
            ToolError empty = session.failed("artifact-begin", begin.withOperationKey(""));
            assertThat(empty.code()).isEqualTo("VALIDATION_FAILED");
            assertThat(empty.issues()).extracting(ToolError.Issue::path).containsExactly("operationKey");
        }
        for (int open = 0; open < properties.maxOpenPerOwner(); open++) {
            session.ok("artifact-begin", begin.withOperationKey("key-" + open), AttachmentOutputs.Begun.class);
        }
    }

    @Test
    @DisplayName("ATA-032: chunk and finish enforce owner isolation when attested, and finish requires attachmentId")
    void chunkAndFinishAreBoundToTheUpload(McpSession session, Scenario scenario) {
        String owner = scenario.actorWith("ata32o", "defect");
        String other = scenario.actorWith("ata32x", "defect");
        byte[] body = textBody(100);
        AttachmentOutputs.Begun begun = session.ok("artifact-begin",
                Begin.of(UUID.randomUUID().toString(), "a.txt", "text/plain", body, owner), AttachmentOutputs.Begun.class);

        assertThat(session.failed("artifact-chunk", Chunk.of(begun.files().getFirst().uploadId(), 0, body, other)).code())
                .isEqualTo("ARTIFACT_NOT_FOUND");
        assertThat(session.failed("artifact-finish", new Finish(begun.attachmentId(), other)).code())
                .isEqualTo("ARTIFACT_NOT_FOUND");
        ToolError missing = session.failed("artifact-finish", new Finish(null, owner));
        assertThat(missing.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(missing.issues()).extracting(ToolError.Issue::path).containsExactly("attachmentId");
    }

    @Test
    @DisplayName("ATA-020: an argument the tool does not declare is refused; status needs exactly one of uploadId and attachmentId; "
            + "a purpose other than evidence is ARTIFACT_UNSUPPORTED_PURPOSE")
    void theBindingRefusesWhatTheContractDoesNot(McpSession session, Scenario scenario) {
        String author = scenario.actorWith("ata20", "defect");
        String id = UUID.randomUUID().toString();

        ToolError unknown = session.failed("artifact-finish",
                Args.from(session.mapper(), new Finish(id, author)).with("path", "/etc/passwd"));
        assertThat(unknown.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(unknown.issues()).extracting(ToolError.Issue::path).containsExactly("path");

        for (Status neither : List.of(new Status(null, null, author), new Status(id, id, author))) {
            ToolError error = session.failed("artifact-status", neither);
            assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
            assertThat(error.issues()).extracting(ToolError.Issue::path).containsExactly("uploadId");
            assertThat(error.issues().getFirst().reason()).contains("exactly one of");
        }
        Begin begin = Begin.of(UUID.randomUUID().toString(), "a.txt", "text/plain", textBody(10), author);
        assertThat(session.failed("artifact-begin", begin.withPurpose("upload")).code())
                .isEqualTo("ARTIFACT_UNSUPPORTED_PURPOSE");
        session.ok("artifact-begin", begin.withPurpose("evidence"), AttachmentOutputs.Begun.class);
    }
}
