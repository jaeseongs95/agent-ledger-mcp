package org.pgledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.domain.rule.ToolContract;
import org.pgledger.mcp.support.McpServerTest;
import org.pgledger.mcp.support.McpSession;
import org.pgledger.mcp.support.SchemaView;

/**
 * ATA-020 for the five attachment tools: the catalog of the running server lists them with the inputs and the outputs of the common
 * design (section 3), with a text for every description (a model reads English: the bundle of the tool texts is English by design) and
 * with an English and a Korean text for every error code a caller can get. The optional {@code attachment_id} of the record tools is
 * asserted with those tools, not here.
 */
@McpServerTest
class AttachmentCatalogTest {

    private static final String ATTESTATION = "_hostAttestation";

    /** A tool, the keys of its input (the attestation is added), the ones that are required, and the keys of its output. */
    private record Expected(String tool, List<String> input, List<String> required, List<String> output) {

        @Override
        public String toString() {
            return tool;
        }
    }

    static Stream<Expected> expected() {
        return Stream.of(
                new Expected("artifact-begin", List.of("operationKey", "fileName", "mediaType", "size", "sha256", "purpose"),
                        List.of("operationKey", "fileName", "mediaType", "size", "sha256"),
                        List.of("uploadId", "maxChunkBytes", "expiresAt")),
                new Expected("artifact-chunk", List.of("uploadId", "sequence", "data", "chunkSha256"),
                        List.of("uploadId", "sequence", "data", "chunkSha256"), List.of("sequence", "received")),
                new Expected("artifact-finish", List.of("uploadId"), List.of("uploadId"),
                        List.of("attachmentId", "size", "sha256")),
                new Expected("artifact-status", List.of("uploadId", "attachmentId"), List.of(),
                        List.of("id", "state", "fileName", "mediaType", "size", "sha256", "expiresAt", "received",
                                "sequence")),
                new Expected("artifact-read", List.of("attachmentId", "offsetBytes", "maxBytes"),
                        List.of("attachmentId", "offsetBytes", "maxBytes"),
                        List.of("attachmentId", "offsetBytes", "totalBytes", "size", "data", "mediaType", "fileName",
                                "text")));
    }

    private static List<String> withAttestation(List<String> keys) {
        return Stream.concat(keys.stream(), Stream.of(ATTESTATION)).toList();
    }

    @Test
    @DisplayName("ATA-020: the catalog lists the five attachment tools of ToolContract once each")
    void theFiveToolsAreListedOnce(McpSession session) {
        assertThat(ToolContract.ATTACHMENT).containsExactly("artifact-begin", "artifact-chunk", "artifact-finish",
                "artifact-status", "artifact-read");
        assertThat(session.tools()).extracting(Tool::name).doesNotHaveDuplicates().containsAll(ToolContract.ATTACHMENT);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("expected")
    @DisplayName("ATA-020: the input schema has the keys of design 3, closed, the required ones, the attestation optional; the output "
            + "schema has the keys of design 3 and no path and no storage key")
    void theSchemasAreThoseOfTheDesign(Expected expected, McpSession session) {
        Tool tool = session.tool(expected.tool());
        SchemaView input = SchemaView.of(session.mapper(), tool.inputSchema());
        SchemaView output = SchemaView.of(session.mapper(), tool.outputSchema());

        assertThat(input.propertyNames()).containsExactlyInAnyOrderElementsOf(withAttestation(expected.input()));
        assertThat(input.required()).containsExactlyInAnyOrderElementsOf(expected.required());
        assertThat(input.closed()).isTrue();
        assertThat(input.properties().get(ATTESTATION).type()).isEqualTo("string");
        assertThat(output.propertyNames()).containsExactlyInAnyOrderElementsOf(expected.output())
                .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("path")
                        || name.toLowerCase(Locale.ROOT).contains("storage"));
    }

    @Test
    @DisplayName("ATA-020: the numbers of the contract are integers and the data of a chunk is a string")
    void theTypesAreThoseOfTheDesign(McpSession session) {
        Map<String, SchemaView> begin = SchemaView.of(session.mapper(), session.tool("artifact-begin").inputSchema())
                .properties();
        Map<String, SchemaView> chunk = SchemaView.of(session.mapper(), session.tool("artifact-chunk").inputSchema())
                .properties();
        Map<String, SchemaView> read = SchemaView.of(session.mapper(), session.tool("artifact-read").inputSchema())
                .properties();

        assertThat(begin.get("size").type()).isEqualTo("integer");
        assertThat(chunk.get("sequence").type()).isEqualTo("integer");
        assertThat(chunk.get("data").type()).isEqualTo("string");
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
    @DisplayName("ATA-020: the descriptions say what a caller needs: uploads only through these tools, the sizes, operationKey required "
            + "and repeatable, the repeated last chunk, and the codes ARTIFACT_BUSY, ARTIFACT_INCOMPLETE, ARTIFACT_HASH_MISMATCH")
    void theDescriptionsSayWhatTheCallerNeeds(McpSession session) {
        String begin = session.tool("artifact-begin").description();
        String chunk = session.tool("artifact-chunk").description();
        String finish = session.tool("artifact-finish").description();
        SchemaView beginInput = SchemaView.of(session.mapper(), session.tool("artifact-begin").inputSchema());

        assertThat(begin).contains("no other way").contains("operationKey is required").contains("repeatable")
                .contains("262144").contains("64 MiB");
        assertThat(beginInput.properties().get("operationKey").description()).contains("Required");
        assertThat(chunk).contains("262144").contains("349528").contains("repeated chunk is answered with the current state")
                .contains("ARTIFACT_BUSY").contains("send the same chunk again later");
        assertThat(finish).contains("ARTIFACT_INCOMPLETE").contains("send the last chunk again")
                .contains("ARTIFACT_HASH_MISMATCH").contains("the bytes are kept");
    }

    @Test
    @DisplayName("ATA-020: the error codes a caller of the attachment tools can get have an English and a Korean text")
    void everyCodeHasEnglishAndKoreanText() {
        ResourceBundle english = ResourceBundle.getBundle("messages/ledger-errors", Locale.ROOT);
        ResourceBundle korean = ResourceBundle.getBundle("messages/ledger-errors", Locale.KOREAN);

        for (String code : List.of("ARTIFACT_UNSUPPORTED_PURPOSE", "ARTIFACT_NOT_FOUND", "ARTIFACT_EXPIRED",
                "ARTIFACT_CHUNK_CONFLICT", "ARTIFACT_BUSY", "ARTIFACT_INCOMPLETE", "ARTIFACT_OPERATION_CONFLICT",
                "ARTIFACT_LIMIT", "ARTIFACT_HASH_MISMATCH", "CHUNK_HASH_MISMATCH", "ARTIFACT_CORRUPTED",
                "ARTIFACT_STORAGE_UNSAFE", "ARTIFACT_KEY_MISSING", "ARTIFACT_STORAGE_FAILED",
                "ARTIFACT_KEY_DIRECTORY_INSIDE_STORAGE")) {
            String key = "error." + code;
            assertThat(english.containsKey(key)).as(key + " in English").isTrue();
            assertThat(korean.containsKey(key)).as(key + " in Korean").isTrue();
            assertThat(korean.getString(key)).as(key).isNotBlank().isNotEqualTo(english.getString(key));
        }
    }
}
