// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.agentledger.testing.CodingScan;
import org.agentledger.testing.SourceRules;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * HK-053: the JSON of the hook is read and written by the one {@link JsonMapper} bean that Spring Boot configures, with the
 * settings of {@code application.properties} ({@code spring.jackson.*}), and the output is the one of the hand-built mapper
 * of the earlier versions (the contract vectors and the fidelity tests of HK-014 run on the same codec).
 */
class JsonMapperTest {

    private static final JsonCodec CODEC = HookContext.bean(JsonCodec.class);

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("HK-053 the application has exactly one JsonMapper bean and the codec of the hook is built on the bean of the context")
    void oneMapperBean() {
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(HookApplication.class).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(JsonMapper.class)).hasSize(1);
                    assertThat(context).hasSingleBean(JsonCodec.class);
                });
    }

    @Test
    @DisplayName("HK-053 no class of the hook builds a JsonMapper, an ObjectMapper or a JSON factory itself")
    void noMapperBuiltByHand() {
        for (Path file : SourceRules.javaFiles(Path.of("src", "main", "java"))) {
            assertThat(CodingScan.strip(file)).as(file.toString())
                    .doesNotContain("JsonMapper.builder(").doesNotContain("new JsonMapper(").doesNotContain("new ObjectMapper(")
                    .doesNotContain("JsonFactory.builder(").doesNotContain("new JsonFactory(");
        }
    }

    @Test
    @DisplayName("HK-053 decimals are read as BigDecimal and written back with their trailing zeros: 0.10 stays 0.10, 1e300 stays exact, never a double")
    void decimalsAreExact() {
        JsonNode tree = CODEC.read(bytes("{\"a\":0.10,\"b\":1e300,\"c\":3.141592653589793238462643383279}"));
        assertThat(tree.path("a").isBigDecimal()).isTrue();
        assertThat(tree.path("a").decimalValue()).isEqualTo(new BigDecimal("0.10"));
        assertThat(tree.path("b").decimalValue()).isEqualByComparingTo(new BigDecimal("1e300"));
        assertThat(CODEC.writeText(tree)).contains("\"a\":0.10").contains("\"c\":3.141592653589793238462643383279");
        // the plain model that the digest reads keeps the decimal as a BigDecimal too
        assertThat(((Map<?, ?>) CODEC.plain(tree)).get("a")).isEqualTo(new BigDecimal("0.10"));
    }

    @Test
    @DisplayName("HK-053 text after the first JSON value is an error, as with JSON.parse")
    void trailingTextIsAnError() {
        assertThatThrownBy(() -> CODEC.read(bytes("{} {}"))).isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> CODEC.readEvent(bytes("{\"tool_name\":\"x\"} x"), FlatEvent.class)).isInstanceOf(JacksonException.class);
    }

    @Test
    @DisplayName("HK-053 nesting up to 256 levels is read, 257 levels are refused as malformed input (never a stack overflow)")
    void nestingDepthIsLimited() {
        assertThat(CODEC.read(bytes("[".repeat(256) + "]".repeat(256)))).isNotNull();
        assertThatThrownBy(() -> CODEC.read(bytes("[".repeat(257) + "]".repeat(257)))).isInstanceOf(JacksonException.class);
    }

    @Test
    @DisplayName("HK-053 unknown fields of a host event are ignored, a field of the wrong type counts as absent, and a document that is no object is no event")
    void hostEventsAreBoundLeniently() {
        FlatEvent event = CODEC.readEvent(bytes("{\"tool_name\":\"t\",\"session_id\":7,\"other\":{\"x\":[1]}}"), FlatEvent.class);
        assertThat(event.toolName()).isEqualTo("t");
        assertThat(event.sessionId()).isNull();
        assertThat(CODEC.readEvent(bytes("[1,2]"), FlatEvent.class)).isNull();
        assertThat(CODEC.readEvent(bytes("null"), FlatEvent.class)).isNull();
    }

    @Test
    @DisplayName("HK-053 the answers are written compact in UTF-8: {} for a record without components, absent fields left out")
    void answersAreCompactUtf8() {
        assertThat(CODEC.write(new HostDocument.Empty())).isEqualTo(bytes("{}"));
        assertThat(CODEC.write(new HostDocument.Decision("deny", "한글", null))).isEqualTo(bytes("{\"decision\":\"deny\",\"reason\":\"한글\"}"));
    }
}
