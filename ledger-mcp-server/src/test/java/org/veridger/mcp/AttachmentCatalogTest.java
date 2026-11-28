// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.veridger.domain.rule.ToolContract;
import org.veridger.mcp.error.ToolError;
import org.veridger.mcp.support.Args;
import org.veridger.mcp.support.AttachmentCalls.Chunk;
import org.veridger.mcp.support.AttachmentCalls.Finish;
import org.veridger.mcp.support.McpServerTest;
import org.veridger.mcp.support.McpSession;
import org.veridger.mcp.support.Scenario;
import org.veridger.mcp.support.SchemaView;

/**
 * ATA-020 for the five snake_case attachment tools aligned with Sennard ArtifactTools and wrapped in ApiResult.v1.
 */
@McpServerTest
class AttachmentCatalogTest {

    private static final String ATTESTATION = "_hostAttestation";

    private record Expected(String tool, List<String> input, List<String> required, List<String> output) {

        @Override
        public String toString() {
            return tool;
        }
    }

    static Stream<Expected> expected() {
        return Stream.of(
                new Expected("artifact_begin", List.of("operationKey", "files", "purpose"),
                        List.of("operationKey", "files"),
                        List.of("attachmentId", "files", "maxChunkBytes", "expiresAt")),
                new Expected("artifact_chunk", List.of("uploadId", "sequence", "data", "chunkSha256"),
                        List.of("uploadId", "sequence", "data", "chunkSha256"), List.of("sequence", "received")),
                new Expected("artifact_finish", List.of("attachmentId"), List.of("attachmentId"),
                        List.of("attachmentId", "files")),
                new Expected("artifact_status", List.of("uploadId", "attachmentId"), List.of(),
                        List.of("kind", "uploadId", "attachmentId", "seq", "state", "fileName", "mediaType", "size",
                                "sha256", "expiresAt", "received", "sequence", "files")),
                new Expected("artifact_read", List.of("attachmentId", "seq", "offsetBytes", "maxBytes"),
                        List.of("attachmentId", "offsetBytes", "maxBytes"),
                        List.of("attachmentId", "seq", "offsetBytes", "totalBytes", "size", "data", "mediaType",
                                "fileName", "text")));
    }

    private static List<String> withSharedOptions(List<String> keys) {
        return Stream.concat(keys.stream(), Stream.of(ATTESTATION, "fields", "structured_only")).toList();
    }

    @Test
    @DisplayName("ATA-020: the catalog lists the five snake_case attachment tools of ToolContract once each")
    void theFiveToolsAreListedOnce(McpSession session) {
        assertThat(ToolContract.ATTACHMENT).containsExactly("artifact_begin", "artifact_chunk", "artifact_finish",
                "artifact_status", "artifact_read");
        assertThat(session.tools()).extracting(Tool::name).doesNotHaveDuplicates().containsAll(ToolContract.ATTACHMENT);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("expected")
    @DisplayName("ATA-020: the input schema has the keys of design 3, closed, the required ones, the attestation optional; the ApiResult.v1 output schema wraps data without path or storage key")
    void theSchemasAreThoseOfTheDesign(Expected expected, McpSession session) {
        Tool tool = session.tool(expected.tool());
        SchemaView input = SchemaView.of(session.mapper(), tool.inputSchema());
        SchemaView output = SchemaView.of(session.mapper(), tool.outputSchema());
        SchemaView data = output.at("data").orElseThrow();

        assertThat(input.propertyNames()).containsExactlyInAnyOrderElementsOf(withSharedOptions(expected.input()));
        assertThat(input.required()).containsExactlyInAnyOrderElementsOf(expected.required());
        assertThat(input.closed()).isTrue();
        assertThat(input.properties().get(ATTESTATION).type()).isEqualTo("string");
        assertThat(output.propertyNames()).containsExactlyInAnyOrder("schemaVersion", "ok", "data", "error");
        assertThat(output.closed()).isTrue();
        assertThat(data.propertyNames()).containsExactlyInAnyOrderElementsOf(expected.output())
                .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("path")
                        || name.toLowerCase(Locale.ROOT).contains("storage"));
    }

    @Test
    @DisplayName("ATA-020: the numbers of the contract are integers and the data of a chunk is a string")
    void theTypesAreThoseOfTheDesign(McpSession session) {
        SchemaView beginSchema = SchemaView.of(session.mapper(), session.tool("artifact_begin").inputSchema());
        Map<String, SchemaView> begin = beginSchema.properties();
        Map<String, SchemaView> chunk = SchemaView.of(session.mapper(), session.tool("artifact_chunk").inputSchema())
                .properties();
        Map<String, SchemaView> read = SchemaView.of(session.mapper(), session.tool("artifact_read").inputSchema())
                .properties();

        assertThat(begin.get("files").type()).isEqualTo("array");
        SchemaView beginItem = beginSchema.at("files[]").orElseThrow();
        SchemaView beginFileSize = beginItem.properties().containsKey("size")
                ? beginItem.properties().get("size")
                : beginSchema.defs().get("BeginFileInput").properties().get("size");
        assertThat(beginFileSize.type()).isEqualTo("integer");
        assertThat(chunk.get("sequence").type()).isEqualTo("integer");
        assertThat(chunk.get("data").type()).isEqualTo("string");
        assertThat(read.get("seq").type()).isEqualTo("integer");
        assertThat(read.get("offsetBytes").type()).isEqualTo("integer");
        assertThat(read.get("maxBytes").type()).isEqualTo("integer");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("expected")
    @DisplayName("ATA-020: every description of the tool, of its input and of its output is a text, not a message key")
    void everyDescriptionIsAText(Expected expected, McpSession session) {
        Tool tool = session.tool(expected.tool());
        List<String> texts = Stream.of(Stream.of(tool.description()),
                SchemaView.of(session.mapper(), tool.inputSchema()).nodes().map(SchemaView::description),
                SchemaView.of(session.mapper(), tool.outputSchema()).nodes().map(SchemaView::description))
                .flatMap(stream -> stream).filter(text -> text != null).toList();

        assertThat(texts).isNotEmpty().allSatisfy(text -> assertThat(text).isNotBlank().doesNotStartWith("tool.")
                .doesNotStartWith("param.").doesNotStartWith("field."));
        SchemaView input = SchemaView.of(session.mapper(), tool.inputSchema());
        expected.input().forEach(key -> assertThat(input.properties().get(key).description()).as(key).isNotBlank());
    }

    @Test
    @DisplayName("ATA-020: upload descriptions explain file and chunk limits, retries, and how to resume")
    void theDescriptionsSayWhatTheCallerNeeds(McpSession session) {
        String begin = session.tool("artifact_begin").description();
        String chunk = session.tool("artifact_chunk").description();
        String finish = session.tool("artifact_finish").description();
        SchemaView beginInput = SchemaView.of(session.mapper(), session.tool("artifact_begin").inputSchema());

        assertThat(begin).contains("1..64 files", "same files on retry", "artifact_chunk", "artifact_finish", "64 MiB");
        assertThat(beginInput.properties().get("operationKey").description()).contains("Required");
        assertThat(chunk).contains("262144", "349528", "repeated chunk returns the current state",
                "ARTIFACT_BUSY", "retry the same chunk later");
        assertThat(finish).contains("ARTIFACT_INCOMPLETE", "resume with artifact_status and artifact_chunk",
                "ARTIFACT_HASH_MISMATCH", "bytes are kept until expiry", "attachment_id");
    }

    @Test
    @DisplayName("OPK-011: artifact_chunk and artifact_finish have no operationKey property, artifact_begin declares and requires it")
    void chunkAndFinishTakeNoOperationKey(McpSession session) {
        SchemaView begin = SchemaView.of(session.mapper(), session.tool("artifact_begin").inputSchema());
        SchemaView chunk = SchemaView.of(session.mapper(), session.tool("artifact_chunk").inputSchema());
        SchemaView finish = SchemaView.of(session.mapper(), session.tool("artifact_finish").inputSchema());

        assertThat(begin.properties()).containsKey("operationKey");
        assertThat(begin.required()).contains("operationKey");
        assertThat(begin.properties().get("operationKey").description()).startsWith("Required.");
        assertThat(chunk.properties()).doesNotContainKey("operationKey");
        assertThat(chunk.required()).doesNotContain("operationKey");
        assertThat(finish.properties()).doesNotContainKey("operationKey");
        assertThat(finish.required()).doesNotContain("operationKey");
        assertThat(session.tool("artifact_begin").description()).contains("caller-chosen operationKey");
        assertThat(session.tool("artifact_chunk").description()).contains("Retry by uploadId, sequence and chunkSha256");
        assertThat(session.tool("artifact_finish").description()).contains("Retry with the same attachmentId");
    }

    @Test
    @DisplayName("OPK-011: an operationKey sent to artifact_chunk or artifact_finish is refused as an unknown argument")
    void anOperationKeyToChunkOrFinishIsUnknown(McpSession session) {
        String uploadId = UUID.randomUUID().toString();
        Args chunk = Args.from(session.mapper(), Chunk.of(uploadId, 0, new byte[] {1}, Scenario.AUTHOR))
                .with("operationKey", UUID.randomUUID().toString());
        Args finish = Args.from(session.mapper(), new Finish(uploadId, Scenario.AUTHOR))
                .with("operationKey", UUID.randomUUID().toString());

        ToolError chunkError = session.failed("artifact_chunk", chunk);
        ToolError finishError = session.failed("artifact_finish", finish);

        ToolError.Issue unknown = new ToolError.Issue("operationKey", "is not a known argument", List.of());
        assertThat(chunkError.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(chunkError.issues()).containsExactly(unknown);
        assertThat(finishError.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(finishError.issues()).containsExactly(unknown);
    }

    @Test
    @DisplayName("ATA-020: the error codes a caller of the attachment tools can get have an English and a Korean text")
    void everyCodeHasEnglishAndKoreanText() {
        ResourceBundle english = ResourceBundle.getBundle("messages/ledger-errors", Locale.ROOT);
        ResourceBundle korean = ResourceBundle.getBundle("messages/ledger-errors", Locale.KOREAN);

        for (String code : List.of("ARTIFACT_UNSUPPORTED_PURPOSE", "ARTIFACT_NOT_FOUND", "ARTIFACT_EXPIRED",
                "ARTIFACT_CHUNK_CONFLICT", "ARTIFACT_BUSY", "ARTIFACT_INCOMPLETE", "ARTIFACT_OPERATION_CONFLICT",
                "ARTIFACT_LIMIT", "ARTIFACT_HASH_MISMATCH", "CHUNK_HASH_MISMATCH", "ARTIFACT_CORRUPTED",
                "ARTIFACT_STORAGE_FULL", "ARTIFACT_STORAGE_UNSAFE", "ARTIFACT_KEY_MISSING", "ARTIFACT_STORAGE_IO_ERROR")) {
            String key = "error." + code;
            assertThat(english.containsKey(key)).as(key + " in English").isTrue();
            assertThat(korean.containsKey(key)).as(key + " in Korean").isTrue();
            assertThat(korean.getString(key)).as(key).isNotBlank().isNotEqualTo(english.getString(key));
        }
    }
}
