package org.pgledger.cli.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgledger.cli.deploy.DeployLayout.Platform;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.PropertyPlaceholderHelper;

/**
 * SPL-003, SPL-004 and SPL-006 (defect D-0031): what each process imports of the secret directory, and what it then has in its
 * {@code Environment}. A configuration tree makes the names of the folders and files under it the property name (Spring Boot,
 * "Configuration Trees"), so the secret directory is never a tree, each service imports the folder {@code common} and its own,
 * the command line tool the folders it needs, and the key files of the attachments are below no tree at all.
 */
class ConfigImportTest {

    private static final String TREE = "optional:configtree:";
    private static final PropertyPlaceholderHelper PLACEHOLDERS = new PropertyPlaceholderHelper("${", "}", ":", null, true);

    @TempDir
    Path tmp;

    private static List<String> trees(String configImport) {
        return List.of(configImport.split(",")).stream().filter(source -> source.startsWith(TREE))
                .map(source -> source.substring(TREE.length())).toList();
    }

    private static String imported(Properties properties, String instance, String home) {
        return PLACEHOLDERS.replacePlaceholders(properties.getProperty("spring.config.import"), placeholder -> {
            if ("ledger.secrets.instance".equals(placeholder)) {
                return instance;
            }
            return "ledger.secrets.home".equals(placeholder) ? home : null;
        });
    }

    private static Properties classPathProperties() throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = new ClassPathResource("application.properties").getInputStream()) {
            properties.load(stream);
        }
        return properties;
    }

    /** The {@code application.properties} of the migration tool, a file of the repository (the module is not on this class path). */
    private static Properties migrationProperties() throws IOException {
        Path root = Path.of(Objects.requireNonNull(System.getProperty("ledger.docs.root"),
                "ledger.docs.root is set by the test task of ledger-cli"));
        Properties properties = new Properties();
        try (InputStream stream = Files.newInputStream(root.resolve("ledger-migrate-1x/src/main/resources/application.properties"))) {
            properties.load(new java.io.InputStreamReader(stream, StandardCharsets.ISO_8859_1));
        }
        return properties;
    }

    @Test
    @DisplayName("SPL-003 each service imports the folder common and its own as configuration trees of their own, never the secret directory: the MCP server common and mcp, the admin web common and admin (Linux and Windows)")
    void spl003_theServicesImportTheirFolders() {
        for (DeployLayout layout : List.of(DeployLayout.linux("pg-ledger-mcp"),
                DeployLayout.windows("pg-ledger-mcp", "C:/ProgramData/pg-ledger-mcp"))) {
            String secrets = layout.etc() + "/secrets";

            assertThat(trees(DeployRenderer.configImport(layout, Service.mcp(layout)))).as(layout.platform().name())
                    .containsExactly(secrets + "/common/", secrets + "/mcp/");
            assertThat(trees(DeployRenderer.configImport(layout, Service.admin(layout)))).as(layout.platform().name())
                    .containsExactly(secrets + "/common/", secrets + "/admin/");
            assertThat(DeployRenderer.configImport(layout, Service.mcp(layout))).doesNotContain(TREE + secrets + "/,")
                    .doesNotContain("/admin/").doesNotContain("/tool/").doesNotEndWith(TREE + secrets + "/");
            assertThat(DeployRenderer.configImport(layout, Service.admin(layout))).doesNotContain("/mcp/").doesNotContain("/tool/");
        }
    }

    @Test
    @DisplayName("SPL-003 the command line tool imports common and mcp (the database password and the token that mcp-check presents), the migration tool common and tool; neither the admin folder, nor the secret directory")
    void spl003_theToolsImportWhatTheyNeed() throws IOException {
        List<String> cli = trees(imported(classPathProperties(), "x", "/etc/x"));
        List<String> migration = trees(imported(migrationProperties(), "x", "/etc/x"));

        assertThat(cli).containsExactly("/etc/x/secrets/common/", "/etc/x/secrets/mcp/");
        assertThat(migration).containsExactly("/etc/x/secrets/common/", "/etc/x/secrets/tool/");
    }

    @Test
    @DisplayName("SPL-004 the key folder of the attachments is below no configuration tree that any service, the command line tool or the migration tool imports (the trees are read from the real strings)")
    void spl004_theKeyFolderIsInNoTree() throws IOException {
        for (DeployLayout layout : List.of(DeployLayout.linux("x"), DeployLayout.windows("x", "C:/ProgramData/x"))) {
            String keys = layout.attachmentKeyDirectory() + "/";
            List<String> all = new java.util.ArrayList<>();
            all.addAll(trees(DeployRenderer.configImport(layout, Service.mcp(layout))));
            all.addAll(trees(DeployRenderer.configImport(layout, Service.admin(layout))));
            all.addAll(trees(imported(classPathProperties(), "x", layout.etc())));
            all.addAll(trees(imported(migrationProperties(), "x", layout.etc())));

            assertThat(all).as(layout.platform().name()).isNotEmpty().noneMatch(keys::startsWith);
            assertThat(keys.startsWith(layout.secretDirectory() + "/")).as("not even below the secret directory").isFalse();
        }
    }

    private static void write(Path file, String value) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, value, StandardCharsets.UTF_8);
    }

    private static String slash(Path path) {
        return path.toString().replace('\\', '/');
    }

    private ConfigurableEnvironment environmentOf(String configImport) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Empty.class).web(WebApplicationType.NONE)
                .run("--spring.config.import=" + configImport, "--spring.config.name=no-file-of-the-product")) {
            return context.getEnvironment();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Empty {
    }

    @Test
    @DisplayName("SPL-006 the Environment of each process holds the secrets of its folders and not those of another: the MCP server has the password and the token of the agents, the admin web the password and the admin secrets, the command line tool the password and the token of the agents; the key file is a property of none (on the base: attachment-keys.<file> in both services)")
    void spl006_theEnvironmentHoldsTheSecretsOfItsFoldersOnly() throws IOException {
        Path etc = tmp.resolve("etc");
        DeployLayout layout = new DeployLayout(Platform.LINUX, "x", slash(tmp.resolve("lib")), slash(etc), slash(tmp.resolve("data")), null);
        String oidc = "spring.security.oauth2.client.registration.google.client-secret";
        write(etc.resolve("secrets/common/ledger.jdbc.password"), "db-password-value");
        write(etc.resolve("secrets/admin/ledger.admin.token"), "admin-token-value");
        write(etc.resolve("secrets/admin/ledger.admin.agent-token"), "agent-token-value");
        write(etc.resolve("secrets/admin/" + oidc), "oidc-secret-value");
        write(etc.resolve("secrets/mcp/ledger.mcp.token"), "mcp-token-value");
        write(etc.resolve("secrets/tool/ledger.import.source.password"), "source-password-value");
        write(etc.resolve("attachment-keys/key-0001"), "key-bytes-value");

        ConfigurableEnvironment mcp = environmentOf(String.join(",", trees(DeployRenderer.configImport(layout, Service.mcp(layout)))
                .stream().map(tree -> TREE + tree).toList()));
        ConfigurableEnvironment admin = environmentOf(String.join(",", trees(DeployRenderer.configImport(layout, Service.admin(layout)))
                .stream().map(tree -> TREE + tree).toList()));
        ConfigurableEnvironment cli = environmentOf(imported(classPathProperties(), "x", slash(etc)));

        assertThat(mcp.getProperty("ledger.jdbc.password")).isEqualTo("db-password-value");
        assertThat(mcp.getProperty("ledger.mcp.token")).isEqualTo("mcp-token-value");
        assertThat(admin.getProperty("ledger.jdbc.password")).isEqualTo("db-password-value");
        assertThat(admin.getProperty("ledger.admin.token")).isEqualTo("admin-token-value");
        assertThat(admin.getProperty("ledger.admin.agent-token")).isEqualTo("agent-token-value");
        assertThat(admin.getProperty(oidc)).isEqualTo("oidc-secret-value");
        assertThat(cli.getProperty("ledger.jdbc.password")).isEqualTo("db-password-value");
        assertThat(cli.getProperty("ledger.mcp.token")).isEqualTo("mcp-token-value");
        for (String name : List.of("ledger.admin.token", "ledger.admin.agent-token", oidc, "ledger.import.source.password")) {
            assertThat(mcp.getProperty(name)).as("the MCP server has no %s", name).isNull();
            assertThat(cli.getProperty(name)).as("the command line tool has no %s", name).isNull();
        }
        assertThat(admin.getProperty("ledger.mcp.token")).as("the admin web has not the token of the agents (it has its copy)").isNull();
        assertThat(admin.getProperty("ledger.import.source.password")).isNull();
        for (ConfigurableEnvironment environment : List.of(mcp, admin, cli)) {
            assertThat(environment.getProperty("attachment-keys.key-0001")).as("the key file is no property").isNull();
            assertThat(environment.getProperty("common.ledger.jdbc.password")).as("a folder name is no part of a property").isNull();
        }
    }
}
