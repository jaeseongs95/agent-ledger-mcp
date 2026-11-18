package org.pgledger.cli.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;

/**
 * ATA-036: where an installation keeps the attachments (the layout of the folders), what is rendered into the settings that the two
 * services and the owner commands read, and the one folder the sandbox of the MCP unit opens for writing. The creation of the
 * folders with owner and mode is {@code PrepareHostCommandTest} (Linux) and {@code WindowsPlatformTest} (Windows); the first key is
 * {@code AttachmentSetupTest}.
 */
class AttachmentDeployTest {

    private static Map<String, String> linux() {
        Map<String, String> values = new HashMap<>();
        values.put(DeployOptions.JAVA, "/usr/lib/jvm/java-25/bin/java");
        values.put(DeployOptions.JDBC_URL, "jdbc:postgresql://db.example.org:5432/ledger");
        values.put(DeployOptions.APP_ACCOUNT, "ledger_app");
        return values;
    }

    private static Map<String, String> windows() {
        Map<String, String> values = new HashMap<>();
        values.put(DeployOptions.PLATFORM, "windows");
        values.put(DeployOptions.HOME, "C:/ProgramData/pg-ledger-mcp");
        values.put(DeployOptions.JAVA, "C:/Program Files/Java/jdk-25/bin/java.exe");
        values.put(DeployOptions.JDBC_URL, "jdbc:postgresql://localhost:5432/ledger");
        values.put(DeployOptions.APP_ACCOUNT, "ledger_app");
        return values;
    }

    private static String file(List<RenderedFile> files, String path) {
        return files.stream().filter(f -> f.path().equals(path)).map(RenderedFile::content).findFirst().orElseThrow();
    }

    private static EnumerablePropertySource<?> yaml(String text) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("rendered",
                new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8)));
        return (EnumerablePropertySource<?>) sources.getFirst();
    }

    @Test
    @DisplayName("ATA-036 the layout: Linux keeps the data in /var/lib/<name> and the attachments below it, the keys in /etc/<name>/attachment-keys (beside the secrets, SPL-004); Windows below its home (data, data/attachments); the key folder is never inside the attachment folder or the secret directory")
    void ata036_theLayout() {
        DeployLayout linux = DeployLayout.linux("x");
        DeployLayout windows = DeployLayout.windows("x", "C:/ProgramData/x");

        assertThat(linux.data()).isEqualTo("/var/lib/x");
        assertThat(linux.attachmentDirectory()).isEqualTo("/var/lib/x/attachments");
        assertThat(linux.attachmentKeyDirectory()).isEqualTo("/etc/x/attachment-keys");
        assertThat(windows.data()).isEqualTo("C:/ProgramData/x/data");
        assertThat(windows.attachmentDirectory()).isEqualTo("C:/ProgramData/x/data/attachments");
        assertThat(windows.attachmentKeyDirectory()).isEqualTo("C:/ProgramData/x/etc/attachment-keys");
        for (DeployLayout layout : List.of(linux, windows)) {
            assertThat(Path.of(layout.attachmentKeyDirectory()).startsWith(Path.of(layout.attachmentDirectory())))
                    .as(layout.platform().name()).isFalse();
            assertThat(layout.attachmentKeyDirectory()).isEqualTo(layout.etc() + "/attachment-keys")
                    .doesNotStartWith(layout.secretDirectory());
        }
    }

    @Test
    @DisplayName("ATA-036 the shared settings carry ledger.attachments.directory and key-directory of the layout on both platforms, never the active key id (setup writes it into local.yaml) and no key; the other keys keep their defaults")
    void ata036_theSharedSettings() throws IOException {
        List<RenderedFile> onLinux = DeployTestBeans.render(linux());
        List<RenderedFile> onWindows = DeployTestBeans.render(windows());

        EnumerablePropertySource<?> unix = yaml(file(onLinux, "/etc/pg-ledger-mcp/application.yaml"));
        assertThat(unix.getProperty("ledger.attachments.directory")).isEqualTo("/var/lib/pg-ledger-mcp/attachments");
        assertThat(unix.getProperty("ledger.attachments.key-directory")).isEqualTo("/etc/pg-ledger-mcp/attachment-keys");
        EnumerablePropertySource<?> win = yaml(file(onWindows, "C:/ProgramData/pg-ledger-mcp/etc/application.yaml"));
        assertThat(win.getProperty("ledger.attachments.directory")).isEqualTo("C:/ProgramData/pg-ledger-mcp/data/attachments");
        assertThat(win.getProperty("ledger.attachments.key-directory"))
                .isEqualTo("C:/ProgramData/pg-ledger-mcp/etc/attachment-keys");
        for (EnumerablePropertySource<?> settings : List.of(unix, win)) {
            assertThat(List.of(settings.getPropertyNames())).filteredOn(name -> name.startsWith("ledger.attachments."))
                    .containsExactlyInAnyOrder("ledger.attachments.directory", "ledger.attachments.key-directory");
        }
        // the service files carry nothing of the attachments: the file they share is the one place
        for (RenderedFile rendered : onLinux) {
            if (rendered.path().endsWith("/mcp.yaml") || rendered.path().endsWith("/admin.yaml")) {
                assertThat(rendered.content()).as(rendered.path()).doesNotContain("attachments");
            }
        }
    }

    @Test
    @DisplayName("ATA-036 the MCP unit may write the attachment folder and nothing else, the admin unit keeps its settings folder as the one writable path (the admin web only reads the attachments)")
    void ata036_theSandboxOfTheUnits() {
        List<RenderedFile> files = DeployTestBeans.render(linux());

        List<String> mcp = file(files, "/etc/systemd/system/pg-ledger-mcp.service").lines()
                .filter(line -> line.startsWith("ReadWritePaths=")).toList();
        List<String> admin = file(files, "/etc/systemd/system/pg-ledger-mcp-admin.service").lines()
                .filter(line -> line.startsWith("ReadWritePaths=")).toList();

        assertThat(mcp).containsExactly("ReadWritePaths=/var/lib/pg-ledger-mcp/attachments");
        assertThat(admin).containsExactly("ReadWritePaths=/etc/pg-ledger-mcp");
    }
}
