package org.pgledger.hook;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.context.config.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * HK-054: the three host dialects are adapter beans behind {@link HostDialect}, chosen by the client of the command line
 * through {@link HostDialects}; the common code branches on no host name (principle 3.2, checklist 30).
 */
class DialectBeansTest {

    private static final JavaClasses HOOK = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("org.pgledger.hook");
    private static final JsonCodec CODEC = HookContext.bean(JsonCodec.class);
    private static final Map<Client, Class<? extends HostDialect>> DIALECT_OF = Map.of(
            Client.CLAUDE, ClaudeDialect.class, Client.CODEX, CodexDialect.class, Client.ANTIGRAVITY, AntigravityDialect.class);

    @Test
    @DisplayName("HK-054 the application context has one HostDialect bean for each client, and HostDialects finds each by its client")
    void oneBeanForEachClient() {
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(HookApplication.class).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(HostDialect.class)).hasSize(Client.values().length);
                    HostDialects dialects = context.getBean(HostDialects.class);
                    for (Client client : Client.values()) {
                        assertThat(dialects.of(client).client()).isEqualTo(client);
                    }
                });
    }

    @ParameterizedTest
    @EnumSource(Client.class)
    @DisplayName("HK-054 the dialect of a client is the one class of its host: claude, codex, antigravity")
    void theDialectOfAClientIsItsOwn(Client client) {
        assertThat(HookContext.bean(HostDialects.class).of(client)).isExactlyInstanceOf(DIALECT_OF.get(client));
    }

    @Test
    @DisplayName("HK-054 a client without a dialect bean, or with two, stops the application (a start error, never a silent choice)")
    void everyClientHasExactlyOneDialect() {
        ClaudeDialect claude = new ClaudeDialect(CODEC);
        CodexDialect codex = new CodexDialect(CODEC);
        AntigravityDialect antigravity = new AntigravityDialect(CODEC);
        assertThatThrownBy(() -> new HostDialects(List.of(claude, codex))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ANTIGRAVITY");
        assertThatThrownBy(() -> new HostDialects(List.of(claude, claude, codex, antigravity)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("CLAUDE");
    }

    @Test
    @DisplayName("HK-054 only the dialects know a concrete dialect: the common classes depend on the HostDialect interface and HostDialects, and Client has no dialect factory")
    void commonCodeKnowsNoConcreteDialect() {
        noClasses().that().areNotAssignableTo(HostDialect.class).should().dependOnClassesThat()
                .areAssignableTo(ClaudeDialect.class)
                .orShould().dependOnClassesThat().areAssignableTo(CodexDialect.class)
                .orShould().dependOnClassesThat().areAssignableTo(AntigravityDialect.class)
                .check(HOOK);
        assertThat(HOOK.get(Client.class).getMethods().stream().map(method -> method.getName())).doesNotContain("dialect");
    }
}
