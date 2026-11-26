// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.context.support.MessageSourceSupport;
import tools.jackson.databind.JsonNode;

/**
 * R9-D0096: what makes a cold start of the hook cost processor time that no hook code asks for. The hook runs as one
 * short {@code java -jar} per host call, and REM-008 starts four of them at once; a machine with four cores spends the
 * processor time of all four, so the time of four starts is about four times the processor time of one. A default of
 * Spring made one start enumerate every locale of the JDK: on the measuring PC that was about 1.3 s of the 4.0 .. 4.7 s
 * of processor time of a start (2.7 .. 2.9 s without it), and four starts on four cores took 4.6 s (up to 5.1 s) instead
 * of 3.5 s. These tests do not measure time (that is HK-017 and REM-008): they check the cause, which a loaded class
 * shows exactly.
 */
@ExtendWith(HookExtension.class)
class ColdStartCostTest {

    /** The class that {@code Locale.getAvailableLocales()} loads to list every locale of every provider of the JDK. */
    private static final String LOCALE_LISTING = "sun.util.locale.provider.LocaleServiceProviderPool$AllAvailableLocales";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(HookApplication.class);

    @Test
    @DisplayName("R9-D0096 the start of the resident program and its first call (changed by the user decision of 2026-11-22: the program is the resident Spring Boot jar) load neither the message source of Spring that lists the JDK locales nor that listing")
    void aColdStartDoesNotListTheLocalesOfTheJdk(HookJar jar, TestWorkspace workspace) throws Exception {
        CodexHost codex = new CodexHost();
        JsonNode input = Json.tree(Map.of("note", "x"));
        Path classLog = workspace.work().resolve("class-load.log");
        // -Xlog with a file writes nothing on stdout or stderr: the answer of the hook stays the only output
        try (ResidentProbe probe = ResidentProbe.start(workspace, workspace.work().resolve("probe-state"),
                List.of("-Xlog:class+load:file=" + classLog), Map.of())) {
            org.agentledger.hookclient.ResidentAddress address = probe.awaitAddress(java.time.Duration.ofSeconds(60))
                    .orElseThrow(() -> new AssertionError("the resident program did not get ready"));
            org.agentledger.hookclient.HookAnswer answer = probe.client().call(address, new org.agentledger.hookclient.HookCall(
                    List.of("codex"), codex.event(codex.toolName("issue_save"), input)));
            assertThat(answer.stderr()).isEmpty();
            Run run = new Run(answer.exit(), answer.stdout(), answer.stderr());
            Attestations.Stamp stamp = Attestations.verify(codex.author(HookFixture.HOST_ID), "issue_save", input,
                    codex.stampedPart(run));
            assertThat(run.document()).isEqualTo(codex.allowed(input, stamp.token()));
        }

        List<String> loaded = Files.readAllLines(classLog, StandardCharsets.UTF_8);
        // the log is the one of this start: the hook and the context of Spring are in it, so an absent class is absent
        assertThat(loaded).anyMatch(line -> line.contains(" org.agentledger.hook.Main source:"));
        assertThat(loaded).anyMatch(line -> line.contains(" org.springframework.context.support.GenericApplicationContext source:"));
        // the lines of the two classes only, so that a failure prints them and not the whole log of the start
        assertThat(loaded.stream().filter(line -> line.contains(" " + LOCALE_LISTING + " "))).isEmpty();
        assertThat(loaded.stream().filter(line -> line.contains(" " + MessageSourceSupport.class.getName() + " "))).isEmpty();
        assertThat(loaded).anyMatch(line -> line.contains(" org.agentledger.hook.HookMessages source:"));
    }

    @Test
    @DisplayName("R9-D0096 the context of the hook has one message source, the one of the hook, and it is not a MessageSourceSupport")
    void theContextHasTheMessageSourceOfTheHook() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(MessageSource.class)).hasSize(1);
            assertThat(context.getBean("messageSource")).isInstanceOf(HookMessages.class).isNotInstanceOf(MessageSourceSupport.class);
            assertThat(context.getMessage("any.code", null, "the default", Locale.ROOT)).isEqualTo("the default");
        });
    }

    @Test
    @DisplayName("R9-D0096 the message source has no bundle: a default message comes back as it is, any other code is unknown")
    void theMessageSourceHasNoBundle() {
        HookMessages messages = new HookMessages();

        assertThat(messages.getMessage("a.code", new Object[] {"{0}"}, "the {0} default", Locale.KOREA)).isEqualTo("the {0} default");
        assertThat(messages.getMessage("a.code", null, null, Locale.ROOT)).isNull();
        assertThatThrownBy(() -> messages.getMessage("a.code", null, Locale.ROOT))
                .isInstanceOf(NoSuchMessageException.class).hasMessageContaining("a.code");
        assertThat(messages.getMessage(new DefaultMessageSourceResolvable(new String[] {"a.code"}, "fallback"), Locale.ROOT))
                .isEqualTo("fallback");
        assertThatThrownBy(() -> messages.getMessage(new DefaultMessageSourceResolvable(new String[] {"first", "last"}), Locale.ROOT))
                .isInstanceOf(NoSuchMessageException.class).hasMessageContaining("last");
        assertThatThrownBy(() -> messages.getMessage(new DefaultMessageSourceResolvable((String[]) null, (Object[]) null, null), Locale.ROOT))
                .isInstanceOf(NoSuchMessageException.class);
    }
}
