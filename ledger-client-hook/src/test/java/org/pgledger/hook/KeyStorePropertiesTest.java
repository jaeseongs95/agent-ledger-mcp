package org.pgledger.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.hook.keys.KeyFolder;
import org.pgledger.hook.keys.KeyStoreProperties;
import org.pgledger.hook.keys.SessionKeyException;
import org.pgledger.hook.keys.SessionKeyStore;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * HK-051: the settings of the hook (the key folder, the home folder and the account) are bound by Boot into
 * {@link KeyStoreProperties}: the environment variable {@code LEDGER_HOOK_KEY_DIR} still names the key folder, and the
 * defaults are the ones of the earlier versions. The environment of a test is a map in the place of the real one, so no
 * test depends on the machine's variables.
 */
class KeyStorePropertiesTest {

    private static final Path KEYS = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("hook-key-dir");
    private static final Path HOME = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("hook-home");

    private static ApplicationContextRunner runner(Map<String, Object> environment, String... properties) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withInitializer(context -> context.getEnvironment().getPropertySources().replace(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environment)))
                .withUserConfiguration(HookApplication.class)
                .withPropertyValues(properties);
    }

    @Test
    @DisplayName("HK-051 the environment variable LEDGER_HOOK_KEY_DIR (an absolute path) is the key folder, whatever the home folder is")
    void theEnvironmentVariableNamesTheKeyFolder() {
        assertThat(KeyFolder.ENVIRONMENT_VARIABLE).isEqualTo("LEDGER_HOOK_KEY_DIR");
        runner(Map.of(KeyFolder.ENVIRONMENT_VARIABLE, KEYS.toString()), "ledger.hook.user-home=" + HOME).run(context -> {
            assertThat(context).hasNotFailed();
            KeyStoreProperties settings = context.getBean(KeyStoreProperties.class);
            assertThat(settings.keyDir()).isEqualTo(KEYS.toString());
            assertThat(settings.folder()).isEqualTo(new KeyFolder.Usable(KEYS));
        });
    }

    @Test
    @DisplayName("HK-051 the variable absent or blank: the key folder is <home>/.pg-ledger-mcp/session-keys")
    void withoutTheVariableTheHomeFolderDecides() {
        KeyFolder inHome = new KeyFolder.Usable(HOME.resolve(".pg-ledger-mcp").resolve("session-keys"));
        runner(Map.of(), "ledger.hook.user-home=" + HOME).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(KeyStoreProperties.class).folder()).isEqualTo(inHome);
        });
        runner(Map.of(KeyFolder.ENVIRONMENT_VARIABLE, "  "), "ledger.hook.user-home=" + HOME).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(KeyStoreProperties.class).folder()).isEqualTo(inHome);
        });
    }

    @Test
    @DisplayName("HK-051 without the variable and without a home folder no key folder is named (Unconfigured), and the application still starts")
    void noFolderAtAll() {
        runner(Map.of(), "ledger.hook.user-home=").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(KeyStoreProperties.class).folder()).isInstanceOf(KeyFolder.Unconfigured.class);
        });
    }

    @Test
    @DisplayName("HK-051 the home folder and the account are those of the process: user.home and user.name")
    void homeAndAccountAreThoseOfTheProcess() {
        runner(Map.of()).run(context -> {
            assertThat(context).hasNotFailed();
            KeyStoreProperties settings = context.getBean(KeyStoreProperties.class);
            assertThat(settings.userHome()).isEqualTo(System.getProperty("user.home"));
            assertThat(settings.userName()).isEqualTo(System.getProperty("user.name"));
        });
    }

    @Test
    @DisplayName("HK-051 a relative key folder does not stop the start: the key store refuses every load with FOLDER_UNAVAILABLE (HK-036)")
    void aRelativeFolderIsRefusedByTheStoreNotByTheStart() {
        runner(Map.of(KeyFolder.ENVIRONMENT_VARIABLE, "keys")).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(KeyStoreProperties.class).folder()).isInstanceOf(KeyFolder.Rejected.class);
            SessionKeyStore store = context.getBean(SessionKeyStore.class);
            assertThatThrownBy(() -> store.load("ledger", "claude:a")).isInstanceOfSatisfying(SessionKeyException.class,
                    refusal -> assertThat(refusal.reason()).isEqualTo(SessionKeyException.Reason.FOLDER_UNAVAILABLE));
        });
    }
}
