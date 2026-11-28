// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.veridger.mcp.support.McpServerTest;
import org.veridger.mcp.support.McpSession;
import org.veridger.mcp.support.SchemaView;
import org.veridger.mcp.support.ToolNames;
import org.veridger.mcp.tool.ToolAdapter;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The texts of the tools: a description that reaches the model is a text, never a message key, and every key that the
 * adapters name exists in the bundle.
 */
@McpServerTest
class CatalogTextsTest {

    private static final List<String> KEY_PREFIXES = List.of("tool.", "param.", "field.");
    private static final Pattern TOOL_REFERENCE = Pattern.compile("\\b(?:ledger|artifact)_[a-z][a-z0-9_]*");

    private static List<String> descriptionsOf(SchemaView schema) {
        return schema.nodes().map(SchemaView::description).filter(text -> text != null).toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.veridger.mcp.support.ToolNames#all")
    @DisplayName("1.5/MCW-017 texts: no description of a tool, of its input or of its output is still a message key")
    void noDescriptionIsAMessageKey(String name, McpSession session) {
        Tool tool = session.tool(name);
        List<String> texts = Stream.of(List.of(tool.description()),
                descriptionsOf(SchemaView.of(session.mapper(), tool.inputSchema())),
                descriptionsOf(SchemaView.of(session.mapper(), tool.outputSchema()))).flatMap(List::stream)
                .toList();

        assertThat(texts).isNotEmpty().allSatisfy(text -> assertThat(text).isNotBlank()
                .satisfies(value -> KEY_PREFIXES.forEach(prefix -> assertThat(value).doesNotStartWith(prefix))));
    }

    @Test
    @DisplayName("1.5/MCW-017 texts: the tools of the server are 33 and each has a clear domain-neutral description")
    void everyToolHasADescription(McpSession session) {
        assertThat(session.tools()).extracting(Tool::name).hasSize(33)
                .containsExactlyInAnyOrderElementsOf(ToolNames.all());
        assertThat(session.tools()).allSatisfy(tool -> assertThat(tool.description()).isNotBlank());
    }

    private static Stream<String> keysOf(Object adapter) {
        Class<?> type = AopUtils.getTargetClass(adapter);
        return Arrays.stream(type.getDeclaredMethods()).filter(method -> method.isAnnotationPresent(McpTool.class))
                .flatMap(CatalogTextsTest::keysOfTool);
    }

    private static Stream<String> keysOfTool(Method method) {
        Stream<String> own = Stream.of(method.getAnnotation(McpTool.class).description());
        Stream<String> parameters = Arrays.stream(method.getParameters())
                .filter(parameter -> parameter.isAnnotationPresent(McpToolParam.class))
                .flatMap(CatalogTextsTest::keysOfParameter);
        return Stream.concat(own, parameters);
    }

    private static Stream<String> keysOfParameter(Parameter parameter) {
        Stream<String> own = Stream.of(parameter.getAnnotation(McpToolParam.class).description());
        Stream<String> components = parameter.getType().isRecord()
                ? Arrays.stream(parameter.getType().getRecordComponents()).map(CatalogTextsTest::keyOfComponent)
                        .flatMap(Stream::ofNullable)
                : Stream.empty();
        return Stream.concat(own, components);
    }

    private static String keyOfComponent(RecordComponent component) {
        JsonPropertyDescription description = component.getAccessor().getAnnotation(JsonPropertyDescription.class);
        return description == null ? null : description.value();
    }

    @Test
    @DisplayName("1.5/MCW-017 texts: every message key that an adapter names exists in both messages/mcp.properties and messages/mcp_ko.properties")
    void everyKeyOfTheAdaptersExists(@Autowired List<ToolAdapter> adapters) throws IOException {
        ResourceBundle english = messages("/messages/mcp.properties");
        ResourceBundle korean = messages("/messages/mcp_ko.properties");
        List<String> used = adapters.stream().flatMap(CatalogTextsTest::keysOf).distinct().toList();

        assertThat(used.stream().filter(key -> key.startsWith("tool.")).count()).isEqualTo(ToolNames.all().size());
        assertThat(used.stream().filter(key -> key.startsWith("param.")).count()).isPositive();
        assertThat(used.stream().filter(key -> key.startsWith("field.")).count()).isPositive();
        assertThat(used.stream().filter(key -> !english.containsKey(key)).toList()).isEmpty();
        assertThat(used.stream().filter(key -> !korean.containsKey(key)).toList()).isEmpty();
    }

    @Test
    void bothToolCatalogsReferOnlyToRegisteredTools() throws IOException {
        for (String bundle : List.of("/messages/mcp.properties", "/messages/mcp_ko.properties")) {
            ResourceBundle descriptions = messages(bundle);
            assertThat(descriptions.keySet().stream().filter(key -> key.startsWith("tool."))
                    .map(key -> key.substring("tool.".length())).toList())
                    .containsExactlyInAnyOrderElementsOf(ToolNames.all());
            for (String tool : ToolNames.all()) {
                String description = descriptions.getString("tool." + tool);
                assertThat(description).isNotBlank();
                assertThat(TOOL_REFERENCE.matcher(description).results().map(MatchResult::group).toList())
                        .as("%s in %s", tool, bundle).isSubsetOf(ToolNames.all());
            }
        }
    }

    private static ResourceBundle messages(String path) throws IOException {
        try (var stream = CatalogTextsTest.class.getResourceAsStream(path)) {
            return new PropertyResourceBundle(stream);
        }
    }
}
