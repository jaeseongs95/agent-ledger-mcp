// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.hook;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/** HK-014: the JSON round trip keeps what the host sent. */
@ExtendWith(HookExtension.class)
class JsonFidelityTest {

    private static final String NUMBERS =
            "{\"huge\":1e300,\"neg\":-0,\"dec\":0.10,\"tiny\":0.000000000000000000000000001,"
                    + "\"exp\":1.5E+3,\"precise\":3.141592653589793238462643383279}";

    private static final String ESCAPES =
            "{\"esc\":\"\\\"\\\\\\/\\b\\f\\n\\r\\t\\u0041\",\"pair\":\"\\ud83d\\ude00\",\"raw\":\"😀 한글 é\","
                    + "\"unknown\":{\"a\":[1,{\"b\":null}],\"c\":true},\"empty\":{},\"list\":[]}";

    private static final String DEPTH_100 = "[".repeat(100) + "1" + "]".repeat(100);

    @ParameterizedTest
    @MethodSource("org.veridger.hook.Hosts#all")
    @DisplayName("HK-014 decimals keep their exact value (no double), and 0.10 keeps its trailing zero")
    void numbersAreExact(HostScenario host, HookFixture fixture) {
        Run run = fixture.runClean(host, host.echoEvent("issue_save", Json.raw(NUMBERS)));
        assertThat(run.out()).contains("\"dec\":0.10");
        JsonNode args = host.echoed(run);
        assertThat(args.path("huge").decimalValue()).isEqualByComparingTo(new BigDecimal("1e300"));
        assertThat(args.path("neg").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(args.path("tiny").decimalValue()).isEqualByComparingTo(new BigDecimal("0.000000000000000000000000001"));
        assertThat(args.path("precise").decimalValue()).isEqualByComparingTo(new BigDecimal("3.141592653589793238462643383279"));
        assertThat(args.path("exp").decimalValue()).isEqualByComparingTo(new BigDecimal("1500"));
    }

    @ParameterizedTest
    @MethodSource("org.veridger.hook.Hosts#all")
    @DisplayName("HK-014 escapes, surrogate pairs and Korean text survive; unknown fields pass through untouched; the output is UTF-8")
    void stringsAndUnknownFields(HostScenario host, HookFixture fixture) {
        Run run = fixture.runClean(host, host.echoEvent("issue_save", Json.raw(ESCAPES)));
        JsonNode args = host.echoed(run);
        assertThat(args.path("esc").stringValue()).isEqualTo("\"\\/\b\f\n\r\tA");
        assertThat(args.path("pair").stringValue()).isEqualTo("😀");
        Attestations.Stamp stamp = fixture.stampCarrier(host.author(HookFixture.HOST_ID), "issue_save", Json.of(ESCAPES), args);
        assertThat(args).isEqualTo(Json.stamped(Json.of(ESCAPES), stamp.token()));
        assertThat(run.stdout()).contains("한글".getBytes(StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @MethodSource("org.veridger.hook.Hosts#all")
    @DisplayName("HK-014 100 levels of nesting and duplicate keys: no crash (the last duplicate wins, as in JSON.parse)")
    void nestingAndDuplicates(HostScenario host, HookFixture fixture) {
        String sent = "{\"deep\":" + DEPTH_100 + ",\"dup\":1,\"dup\":2}";
        Run run = fixture.runClean(host, host.echoEvent("issue_save", Json.raw(sent)));
        JsonNode args = host.echoed(run);
        assertThat(args.path("deep")).isEqualTo(Json.of(DEPTH_100));
        assertThat(args.path("dup").intValue()).isEqualTo(2);
        fixture.stampCarrier(host.author(HookFixture.HOST_ID), "issue_save", Json.of(sent), args);
    }

    @ParameterizedTest
    @MethodSource("org.veridger.hook.Hosts#all")
    @DisplayName("HK-014 nesting far beyond any real call is rejected as malformed input, never a stack overflow")
    void absurdNestingIsRejected(HostScenario host, HookFixture fixture) {
        String deep = "[".repeat(5000) + "]".repeat(5000);
        Run run = fixture.run(host, host.echoEvent("issue_save", Json.raw("{\"deep\":" + deep + "}")));
        InputErrorTest.assertMalformedAnswer(host, run);
    }

    @ParameterizedTest
    @MethodSource("org.veridger.hook.Hosts#all")
    @DisplayName("HK-014 a 1 MB body is handled in process well inside the time budget")
    void oneMegabyteBody(HostScenario host, HookFixture fixture) {
        String text = "한글abc".repeat(120_000);
        JsonNode input = Json.tree(Map.of("body", text, "n", 9007199254740991L));
        byte[] event = host.echoEvent("issue_save", input);
        assertThat(event.length).isGreaterThan(1_000_000);
        long start = System.nanoTime();
        Run run = fixture.runClean(host, event);
        long millis = (System.nanoTime() - start) / 1_000_000;
        JsonNode args = host.echoed(run);
        Attestations.Stamp stamp = fixture.stampCarrier(host.author(HookFixture.HOST_ID), "issue_save", input, args);
        assertThat(args).isEqualTo(Json.stamped(input, stamp.token()));
        assertThat(millis).as("in-process milliseconds").isLessThan(3_000);
    }

    @Test
    @DisplayName("HK-014 Antigravity Arguments given as JSON text: numbers and Korean text survive the parse and re-serialisation")
    void numbersInsideJsonTextArguments(HookFixture fixture) {
        AntigravityHost agy = AntigravityHost.INSTANCE;
        Run run = fixture.runClean(agy, agy.echoEvent("issue_save", NUMBERS));
        String text = agy.echoed(run).stringValue();
        assertThat(text).contains("\"dec\":0.10");
    }

    @ParameterizedTest
    @MethodSource("org.veridger.hook.Hosts#all")
    @DisplayName("HK-014 large integers and decimals pass through untouched")
    void largeNumbersPassThrough(HostScenario host, HookFixture fixture) {
        String sent = "{\"max\":9007199254740991,\"min\":-9007199254740991,\"dec\":0.10,\"huge\":1e300,\"float\":12345678901234567890.0}";
        Run run = fixture.runClean(host, host.echoEvent("issue_save", Json.raw(sent)));
        JsonNode args = host.echoed(run);
        assertThat(args.path("max").longValue()).isEqualTo(9007199254740991L);
        assertThat(args.path("min").longValue()).isEqualTo(-9007199254740991L);
        assertThat(run.out()).contains("\"dec\":0.10");
        fixture.stampCarrier(host.author(HookFixture.HOST_ID), "issue_save", Json.of(sent), args);
    }
}
