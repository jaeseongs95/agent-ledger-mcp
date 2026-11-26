// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.cli.secret.ProcessUser;
import org.veridger.cli.seed.InstallDefaults;
import org.veridger.cli.seed.PackagedSeed;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * SET-008: the install defaults moved into the seed bundle, the rendered files did not change. The digests are those of the
 * files the renderer produced for these inputs before the unit SETUP (taken from the code of that commit, with the defaults as
 * constants of the code): every file of the three targets, byte for byte, path and content.
 */
class DeployGoldenTest {

    /** A Linux server. */
    private static Map<String, String> linuxPlain() {
        Map<String, String> values = new HashMap<>();
        values.put(DeployOptions.JAVA, "/usr/lib/jvm/java-25/bin/java");
        values.put(DeployOptions.JDBC_URL, "jdbc:postgresql://db.example.org:5432/ledger");
        values.put(DeployOptions.APP_ACCOUNT, "ledger_app");
        return values;
    }

    /** A personal PC: Windows, no host names. */
    private static Map<String, String> personalPc() {
        Map<String, String> values = new HashMap<>();
        values.put(DeployOptions.PLATFORM, "windows");
        values.put(DeployOptions.HOME, "C:/ProgramData/veridger");
        values.put(DeployOptions.JAVA, "C:/Program Files/Java/jdk-25/bin/java.exe");
        values.put(DeployOptions.JDBC_URL, "jdbc:postgresql://localhost:5432/ledger");
        values.put(DeployOptions.APP_ACCOUNT, "ledger_app");
        return values;
    }

    /** A Windows server: the same, with the public host names of the reverse proxy the operator put in front. */
    private static Map<String, String> windowsServer() {
        Map<String, String> values = personalPc();
        values.put(DeployOptions.MCP_HOST, "mcp.example.org");
        values.put(DeployOptions.ADMIN_HOST, "admin.example.org");
        return values;
    }

    private static Map<String, Map<String, String>> targets() {
        Map<String, Map<String, String>> targets = new LinkedHashMap<>();
        targets.put("linux plain", linuxPlain());
        targets.put("personal pc", personalPc());
        targets.put("windows server", windowsServer());
        return targets;
    }

    /**
     * ATA-036: the settings of the attachments ({@code ledger.attachments.directory} and {@code key-directory}, the last block of
     * the shared settings file) and the one folder the MCP unit may write ({@code ReadWritePaths=/var/lib/<name>/attachments})
     * joined the rendered files after these digests were taken. They are cut off here, so that the digests keep saying that
     * everything else is as it was; what they hold is the test AttachmentDeployTest.
     */
    private static String withoutAttachments(String content) {
        return content.replaceAll("(?m)^  attachments:\\n(?:    .*\\n)*", "")
                .replaceAll("(?m)^ReadWritePaths=/var/lib/[^\\n]*/attachments\\n", "");
    }

    /**
     * RUN-006: the setting of the long text type (and the option of the installer that wrote it) is gone, the long text type is declared
     * by the dialect of SqlBuilder. The one target that gave the option, "linux with site" (it passed {@code TEXT}), rendered one line of the
     * setting below the schema name; the digests below are those with that line. So the line is put back here, at the place it had,
     * before the content is hashed: the digests keep their values exactly when the files differ from the old ones by that one line and
     * by nothing else (the way PSK-INS-01 proved its block by cutting it, in the other direction).
     */
    private static String withRemovedLongTextLine(String content) {
        String removedKey = "long-text" + "-type";
        return content.replaceAll("(?m)^(  schema:\\n    name: .*\\n)", "$1    " + removedKey + ": 'TEXT'\n");
    }

    /**
     * PCS (ServiceSettings): the example setting named in the comment of the shared settings file is {@code ledger.admin.oidc.owner-email}
     * now (the list {@code owners} was never released). The old name is put back here, as the long text line above, so that the digests
     * keep saying that everything else is as it was.
     */
    private static String withOldOwnersExample(String content) {
        return content.replace("(for example ledger.admin.oidc.owner-email) belong", "(for example ledger.admin.oidc.owners) belong");
    }

    /**
     * SPL-003 (D-0031): each service imports {@code secrets/common/} and its own folder ({@code secrets/mcp/} or
     * {@code secrets/admin/}) instead of {@code secrets/}. Putting the single tree back here proves that nothing else in the
     * rendered service definitions or settings changed; {@code ConfigImportTest} and {@code DeployRendererTest} check the split trees.
     */
    private static String withSingleSecretsTree(String content) {
        return content.replaceAll(
                ",optional:configtree:([^,\\n<]*)/secrets/common/,optional:configtree:\\1/secrets/(?:mcp|admin)/",
                ",optional:configtree:$1/secrets/");
    }

    /**
     * 3.0.0: the default instance name changed from {@code pg-ledger-mcp} to {@code veridger}. Replacing the new name
     * back with the old name in both path and content before hashing proves that nothing else in the rendered files changed.
     */
    private static String withOldInstanceName(String text) {
        return text.replace("veridger", "pg-ledger-mcp");
    }

    /**
     * 4.1.0: {@code ServiceSettings.admin} emits {@code ledger.admin.update.directory} pointing to {@code <etc>/update} so
     * the Admin Web updater works on Windows and custom-named Linux instances. Stripping those two lines here proves nothing
     * else in the rendered files changed while the dedicated assertion below checks that the setting is present.
     */
    private static String withoutUpdateDirectory(String content) {
        return content.replaceAll("(?m)^    update:\\n      directory: '[^\\n]*/update'\\n", "");
    }

    /** The SHA-256 of path and content of every rendered file, in the order the renderer gives them. */
    private static String digest(List<RenderedFile> files) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (RenderedFile file : files) {
                digest.update((withOldInstanceName(file.path()) + "\u0000"
                        + withOldInstanceName(withSingleSecretsTree(
                                withOldOwnersExample(withRemovedLongTextLine(
                                        withoutUpdateDirectory(withoutAttachments(file.content()))))))
                        + "\u0000")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // FUP-NGX-03 changed the digest of "linux with site" on purpose (it renders the operator's certificate site, whose 443 blocks now
    // have `server_tokens off;`); the other three targets are unchanged. PSK-INS-01 then changed "linux with site" and "windows server"
    // on purpose (the two targets with --admin-host: admin.yaml now carries ledger.admin.passkey.rp-id and origins, the passkey: block of
    // four lines); checked by rendering both again without that block, which gave the digests above (f18fa93f..., 4e1ec84d...). The two
    // targets without a public admin name are unchanged.
    // PCS removed the target "linux with site": the nginx site and the certificate options do not exist any more. The other three
    // targets keep their digests (the one host name of "windows server" renders as the list of one name did).
    private static final Map<String, String> BEFORE_SETUP = Map.of(
            "linux plain", "a7cc4e0a5d1ff750a4044e9f07bd99bad4bd6b897f8c9fa4779d1bd5ddfbb8e9",
            "personal pc", "09d2be30498adb057b386356b338b0c6be703833cabd16f4f80e0bf5bb595ef1",
            "windows server", "819c9e62752b66bc2029cb0d6a910426ebffdba725d12133ef59ac3dcdf9b5b9");

    @Test
    @DisplayName("SET-008 the files rendered for the three targets are byte for byte those of the renderer before the unit SETUP (the defaults moved, the files did not)")
    void set008_theRenderedFilesAreUnchanged() {
        Map<String, String> actual = new LinkedHashMap<>();
        targets().forEach((name, values) -> actual.put(name, digest(DeployTestBeans.render(values))));

        assertThat(actual).isEqualTo(BEFORE_SETUP);
        targets().forEach((name, values) -> {
            RenderedFile adminYaml = DeployTestBeans.render(values).stream()
                    .filter(f -> f.path().endsWith("/admin.yaml")).findFirst().orElseThrow();
            assertThat(adminYaml.content()).as("update.directory in %s", name)
                    .contains("    update:\n      directory: '").contains("/update'\n");
        });
    }

    @Test
    @DisplayName("W7-SP-01 the production wiring (the components found by an ApplicationContextRunner: ServicePlatforms, both platforms, the reader and the renderer, no hand assembly) renders the files of SET-008 byte for byte")
    void w7sp01_theProductionWiringRendersTheSameFiles() {
        new ApplicationContextRunner().withBean(ProcessUser.class, () -> fileSystem -> () -> "tester")
                .withBean(Validator.class, () -> Validation.buildDefaultValidatorFactory().getValidator())
                .withUserConfiguration(ServicePlatforms.class, LinuxPlatform.class, WindowsPlatform.class, DeployRenderer.class,
                        DeployInputReader.class, DeployFiles.class, HostFiles.class, WindowsAccess.class,
                        SystemHostAccounts.class, InstallDefaults.class, PackagedSeed.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Map<String, String> actual = new LinkedHashMap<>();
                    targets().forEach((name, values) -> actual.put(name, digest(context.getBean(DeployRenderer.class)
                            .render(context.getBean(DeployInputReader.class).parameters(values)))));

                    assertThat(actual).isEqualTo(BEFORE_SETUP);
        targets().forEach((name, values) -> {
            RenderedFile adminYaml = DeployTestBeans.render(values).stream()
                    .filter(f -> f.path().endsWith("/admin.yaml")).findFirst().orElseThrow();
            assertThat(adminYaml.content()).as("update.directory in %s", name)
                    .contains("    update:\n      directory: '").contains("/update'\n");
        });
                });
    }

    @Test
    @DisplayName("SET-008 giving the name and the two ports of the defaults explicitly renders the same files as leaving them out")
    void set008_explicitDefaultsRenderTheSame() {
        for (Map.Entry<String, Map<String, String>> target : targets().entrySet()) {
            Map<String, String> explicit = new HashMap<>(target.getValue());
            InstallDefaultsHolder.fill(explicit);

            assertThat(DeployTestBeans.render(explicit)).as(target.getKey()).isEqualTo(DeployTestBeans.render(target.getValue()));
        }
    }

    /** The options of the defaults, written out from the install defaults. */
    private static final class InstallDefaultsHolder {

        private static void fill(Map<String, String> values) {
            values.put(DeployOptions.NAME, DeployTestBeans.defaults().instanceName());
            values.put(DeployOptions.MCP_PORT, String.valueOf(DeployTestBeans.defaults().mcpPort()));
            values.put(DeployOptions.ADMIN_PORT, String.valueOf(DeployTestBeans.defaults().adminPort()));
        }
    }
}
