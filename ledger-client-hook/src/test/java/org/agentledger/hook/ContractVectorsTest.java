// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.agentledger.testing.ContractVectors;
import tools.jackson.databind.JsonNode;

/**
 * HK-042, HK-043: the contract vectors of the author format and of the ledger tool names. The hook (its own
 * program, which shares only the attestation module with the server) and the server are two programs that each define these; the vector files of the test fixture
 * of {@code ledger-attestation} are what both are tested against (the server side runs the same files).
 */
@ExtendWith(HookExtension.class)
class ContractVectorsTest {

    private static JsonNode vectors(String resource) {
        return Json.MAPPER.readTree(ContractVectors.read(resource));
    }

    private static List<String> texts(JsonNode array) {
        List<String> texts = new ArrayList<>();
        array.forEach(node -> texts.add(node.stringValue()));
        return texts;
    }

    // ---------------------------------------------------------------- HK-042 author

    static Stream<Arguments> validAuthors() {
        return texts(vectors(ContractVectors.AUTHORS).path("valid")).stream()
                .map(text -> Arguments.argumentSet(abbreviated(text), text));
    }

    /** The invalid vectors; a JSON null is the Java null (no author at all). */
    static Stream<Arguments> invalidAuthors() {
        List<Arguments> cases = new ArrayList<>();
        vectors(ContractVectors.AUTHORS).path("invalid").forEach(node -> {
            String value = node.path("value").isNull() ? null : node.path("value").stringValue();
            cases.add(Arguments.argumentSet(node.path("why").stringValue(), value));
        });
        return cases.stream();
    }

    private static String abbreviated(String text) {
        return text.length() > 40 ? text.substring(0, 37) + "... (" + text.length() + ")" : text;
    }

    @ParameterizedTest
    @MethodSource("validAuthors")
    @DisplayName("HK-042 every valid author vector is an Author, and its parts put together give the vector back")
    void everyValidVectorIsAnAuthor(String text) {
        Author author = Author.parse(text).orElseThrow(() -> new AssertionError("not accepted: " + text));
        assertThat(author.value()).isEqualTo(text);
        assertThat(author.vendor() + ":" + author.session().value()).isEqualTo(text);
    }

    @ParameterizedTest
    @MethodSource("invalidAuthors")
    @DisplayName("HK-042 no invalid author vector is an Author (the whole text counts, nothing is trimmed)")
    void noInvalidVectorIsAnAuthor(String text) {
        assertThat(Author.parse(text)).isEmpty();
    }

    @Test
    @DisplayName("HK-042 the value refuses what the format refuses: its compact constructor checks the vendor and the owner rule")
    void theValueChecksItself() {
        HostId session = new HostId("abc");
        assertThatThrownBy(() -> new Author("Claude", session)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Author("", session)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Author("user", session)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Author(null, session)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Author("claude", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HostId("a b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HostId("")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Author("user", new HostId("owner")).value()).isEqualTo("user:owner");
    }

    @Test
    @DisplayName("HK-042 the author of a vendor and a host id is <vendor prefix>:<host id>, and the three prefixes are claude, codex, gemini")
    void authorOfAVendorAndAHostId() {
        HostId id = new HostId("parent-1234_ab.cd");
        assertThat(Author.of(Vendor.CLAUDE, id).value()).isEqualTo("claude:parent-1234_ab.cd");
        assertThat(Author.of(Vendor.CODEX, id).value()).isEqualTo("codex:parent-1234_ab.cd");
        assertThat(Author.of(Vendor.GEMINI, id).value()).isEqualTo("gemini:parent-1234_ab.cd");
        for (Vendor vendor : Vendor.values()) {
            assertThat(Author.parse(Author.of(vendor, id).value())).as(vendor.name()).isPresent();
        }
    }

    // ---------------------------------------------------------------- HK-043 tool names

    @Test
    @DisplayName("HK-043 the write tools of the hook are exactly the thirteen write tool vectors and the five attachment tool vectors (ATA-021), in the same order")
    void writeToolsAreTheVectors() {
        JsonNode tools = vectors(ContractVectors.WRITE_TOOLS);
        List<String> expected = new ArrayList<>(texts(tools.path("write")));
        expected.addAll(texts(tools.path("attachment")));
        assertThat(Arrays.stream(WriteTool.values()).map(WriteTool::canonicalName).toList()).isEqualTo(expected);
        assertThat(expected).hasSize(18);
    }

    @Test
    @DisplayName("HK-043 ATA-021 the attestation of an attachment tool is issued for its snake_case name, and only whoami and the attachment tools carry no operation key")
    void attachmentToolsAreSnakeCaseAndHaveNoOperationKey() {
        List<String> attachment = texts(vectors(ContractVectors.WRITE_TOOLS).path("attachment"));
        assertThat(attachment).containsExactly("artifact_begin", "artifact_chunk", "artifact_finish", "artifact_status", "artifact_read");
        for (String name : attachment) {
            assertThat(WriteTool.ofName(name)).as(name).isPresent();
            assertThat(WriteTool.ofName(name).orElseThrow().canonicalName()).isEqualTo(name);
            assertThat(WriteTool.ofName(name).orElseThrow().carriesOperationKey()).as(name).isFalse();
        }
        assertThat(Arrays.stream(WriteTool.values()).filter(tool -> !tool.carriesOperationKey()).map(WriteTool::canonicalName))
                .containsExactly("ledger_whoami", "artifact_begin", "artifact_chunk", "artifact_finish", "artifact_status",
                        "artifact_read");
    }

    @Test
    @DisplayName("OPK-016 the contract vectors name the twelve ledger write tools that carry the operationKey: WriteTool.carriesOperationKey() is true for exactly those (the thirteen write vectors minus ledger_whoami) and false for whoami, the five attachment tools and every read tool")
    void exactlyTheTwelveLedgerWriteToolsCarryTheOperationKey() {
        JsonNode tools = vectors(ContractVectors.WRITE_TOOLS);
        List<String> twelve = new ArrayList<>(texts(tools.path("write")));
        assertThat(twelve.remove("ledger_whoami")).isTrue();
        assertThat(twelve).containsExactly("ledger_issue_save", "ledger_spec_save", "ledger_task_save", "ledger_criterion_save",
                "ledger_transition", "ledger_record_deliverable", "ledger_record_case",
                "ledger_link", "ledger_correct", "ledger_batch", "ledger_review", "ledger_review_correct");
        for (String name : twelve) {
            assertThat(WriteTool.ofName(name).orElseThrow().carriesOperationKey()).as(name).isTrue();
        }
        assertThat(Arrays.stream(WriteTool.values()).filter(WriteTool::carriesOperationKey).map(WriteTool::canonicalName))
                .containsExactlyElementsOf(twelve);
        assertThat(WriteTool.WHOAMI.carriesOperationKey()).isFalse();
        for (String name : texts(tools.path("attachment"))) {
            assertThat(WriteTool.ofName(name).orElseThrow().carriesOperationKey()).as(name).isFalse();
        }
        for (String read : texts(tools.path("read"))) {
            assertThat(WriteTool.ofName(read)).as(read + " is no write tool, so it carries no key").isEmpty();
        }
        // the vector file speaks of the operationKey and no longer of a request id
        assertThat(tools.path("description").stringValue()).contains("operationKey").doesNotContainIgnoringCase("request_id");
    }

    @Test
    @DisplayName("OPK-022 the vector lists operationKeyClaim and operationKeyArgument: carriesOperationKey() is true exactly for operationKeyClaim; operationKeyArgument is operationKeyClaim plus artifact_begin; both only name write or attachment tools")
    void theOperationKeyListsMatchTheHook() {
        JsonNode tools = vectors(ContractVectors.WRITE_TOOLS);
        List<String> claim = texts(tools.path("operationKeyClaim"));
        List<String> argument = texts(tools.path("operationKeyArgument"));
        List<String> known = new ArrayList<>(texts(tools.path("write")));
        known.addAll(texts(tools.path("attachment")));

        assertThat(claim).as("operationKeyClaim is in the file").hasSize(12).doesNotHaveDuplicates();
        assertThat(argument).as("operationKeyArgument is in the file").hasSize(13).doesNotHaveDuplicates();
        assertThat(known).containsAll(claim).containsAll(argument);
        assertThat(claim).doesNotContain("ledger_whoami", "artifact_begin", "artifact_chunk", "artifact_finish",
                "artifact_status", "artifact_read");

        assertThat(Arrays.stream(WriteTool.values()).filter(WriteTool::carriesOperationKey).map(WriteTool::canonicalName))
                .containsExactlyInAnyOrderElementsOf(claim);
        for (String name : known) {
            assertThat(WriteTool.ofName(name).orElseThrow().carriesOperationKey()).as(name).isEqualTo(claim.contains(name));
        }

        List<String> expectedArgument = new ArrayList<>(claim);
        expectedArgument.add("artifact_begin");
        assertThat(argument).containsExactlyInAnyOrderElementsOf(expectedArgument);
        // the one tool that takes the argument without having it in its claim is artifact_begin
        assertThat(argument.stream().filter(name -> !claim.contains(name))).containsExactly("artifact_begin");
    }

    @Test
    @DisplayName("HK-043 no read tool vector is a write tool of the hook: the hook never signs for a read tool")
    void readToolsAreNotWriteTools() {
        for (String read : texts(vectors(ContractVectors.WRITE_TOOLS).path("read"))) {
            assertThat(read).startsWith("ledger_");
            assertThat(WriteTool.ofName(read)).as(read).isEmpty();
            assertThat(WriteTool.ofName(read.substring("ledger_".length()))).as(read).isEmpty();
        }
    }

    @Test
    @DisplayName("HK-043 the vector files are UTF-8 JSON that the hook tests can read")
    void vectorFilesAreReadable() {
        assertThat(StandardCharsets.UTF_8.decode(ByteBuffer.wrap(ContractVectors.read(ContractVectors.AUTHORS))).toString()).contains("claude:abc");
        assertThat(StandardCharsets.UTF_8.decode(ByteBuffer.wrap(ContractVectors.read(ContractVectors.WRITE_TOOLS))).toString())
                .contains("ledger_issue_save");
    }
}
