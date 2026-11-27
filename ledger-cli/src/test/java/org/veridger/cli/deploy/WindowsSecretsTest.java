// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.veridger.cli.ErrorCode;
import org.veridger.files.WellKnownAccount;
import org.veridger.cli.support.CliExtension;
import org.veridger.cli.support.CliExtension.Cli;
import org.veridger.cli.support.CliExtension.Outcome;
import org.veridger.cli.support.EnglishMessages;
import org.veridger.cli.support.RecordingOutput;
import org.veridger.cli.support.RequiresNonPosixFileSystem;
import org.veridger.cli.support.ScriptedSecretInput;
import org.veridger.cli.support.WindowsEffectiveRights;

/**
 * PCS-035, PCS-036 (and WSEC-12, changed): {@code prepare-host} on a real file system with ACLs (Windows), run twice, with a secret
 * of the installing user in between, and a re-run that fails. The service account LocalService must read what the installing user
 * made (PCS-031: the effective rights are computed by the Windows API from the ACL; running as LocalService is not done). Closing the
 * home to everybody but SYSTEM and Administrators needs an elevated prompt, so these cases are skipped (an assumption, not a failure)
 * in a prompt that is not elevated and run on the Windows test server. The order of the steps is {@link WindowsPlatformTest}, which
 * runs everywhere. There is nothing to take back: the secrets are never handed to the service account.
 */
@ExtendWith({ CliExtension.class, EnglishMessages.class })
class WindowsSecretsTest {

    private static final String HOME_OPTION = "--home=C:/ProgramData/veridger";
    private static final String JAVA = "--java=C:/Program Files/Java/jdk-25/bin/java.exe";
    private static final WellKnownAccount SERVICE = WellKnownAccount.SERVICE;

    @TempDir
    Path tmp;

    /** Whether this process may change what only an administrator may: it can list the configuration of the system. */
    private static boolean elevated() {
        try (DirectoryStream<Path> configuration = Files.newDirectoryStream(Path.of("C:/Windows/System32/config"))) {
            return configuration != null;
        } catch (IOException e) {
            return false;
        }
    }

    private Path home() {
        return tmp.resolve("root/C/ProgramData/veridger");
    }

    private Path jars() throws IOException {
        Path jars = tmp.resolve("jars");
        if (!Files.exists(jars)) {
            Files.createDirectories(jars);
            for (String jar : List.of(DeployLayout.CLI_JAR, DeployLayout.MCP_JAR, DeployLayout.ADMIN_JAR, "driver.jar", "WinSW.exe")) {
                Files.write(jars.resolve(jar), ("content of " + jar).getBytes(StandardCharsets.UTF_8));
            }
        }
        return jars;
    }

    private Outcome prepareHost(Cli cli) throws IOException {
        return cli.run(tmp.resolve("secrets"), null, new RecordingOutput(), "prepare-host", "--jars=" + jars(),
                "--driver-jar=" + jars().resolve("driver.jar"), "--root=" + tmp.resolve("root"),
                "--jdbc-url=jdbc:postgresql://db.example.org:5432/ledger", "--app-account=ledger_app", "--platform=windows",
                HOME_OPTION, "--winsw=" + jars().resolve("WinSW.exe"), JAVA);
    }

    /** Stores a secret through the command, the real {@code SecretStore} path (the ACL it creates, the temporary file and the move). */
    private void storeSecret(Cli cli, String name, String value) {
        Outcome stored = cli.run(home().resolve("etc/secrets"), new ScriptedSecretInput(value.toCharArray()), new RecordingOutput(),
                "secret-set", name);
        assertThat(stored.code()).as("secret-set of %s", name).isEmpty();
    }

    private static List<AclEntry> acl(Path path) throws IOException {
        return Files.getFileAttributeView(path, AclFileAttributeView.class).getAcl();
    }

    private static List<List<AclEntry>> acls(List<Path> paths) throws IOException {
        List<List<AclEntry>> all = new ArrayList<>();
        for (Path path : paths) {
            all.add(acl(path));
        }
        return all;
    }

    @Test
    @RequiresNonPosixFileSystem
    @DisplayName("PCS-036 prepare-host, a secret of the installing user, prepare-host again: both runs succeed, the secret and its folders are unchanged, and the service account LocalService has the effective right to read the secret, the secrets folder, the settings folder and the jars, to write only driver-state and update (not etc), and nothing on the secrets but read; Everyone has no right on the secrets")
    void pcs036_aRerunAndTheServiceAccountReads(Cli cli) throws IOException {
        assumeTrue(elevated(), "this needs an elevated prompt: only an administrator may close the home to everybody else");
        assertThat(prepareHost(cli).code()).as("the first run").isEmpty();
        Path secrets = home().resolve("etc/secrets");
        Path keys = home().resolve("etc/attachment-keys");
        Path folder = secrets.resolve("admin");
        Path secret = folder.resolve("ledger.admin.token");
        storeSecret(cli, "ledger.admin.token", "never-printed-0123456789");
        List<Path> paths = List.of(home(), home().resolve("lib"), home().resolve("etc"), secrets, keys, secret, folder);
        List<List<AclEntry>> before = acls(paths);

        Outcome rerun = prepareHost(cli);

        assertThat(rerun.code()).as("the re-run").isEmpty();
        assertThat(Files.readString(secret)).isEqualTo("never-printed-0123456789");
        assertThat(acl(secrets)).as("the secrets folder is not changed by a re-run").isEqualTo(before.get(3));
        assertThat(acl(keys)).isEqualTo(before.get(4));
        assertThat(acl(secret)).isEqualTo(before.get(5));
        assertThat(acl(folder)).as("the folder of the use is not changed by a re-run").isEqualTo(before.get(6));
        assertThat(acl(secret)).as("the service account and the installing user only").hasSize(2);
        for (Path path : List.of(secrets, folder, keys, secret)) {
            int service = WindowsEffectiveRights.of(path, SERVICE);
            assertThat(service & WindowsEffectiveRights.FILE_READ_DATA).as("read of %s", path).isNotZero();
            assertThat(service & (WindowsEffectiveRights.FILE_WRITE_DATA | WindowsEffectiveRights.DELETE
                    | WindowsEffectiveRights.WRITE_DAC | WindowsEffectiveRights.WRITE_OWNER)).as("no change of %s", path).isZero();
            assertThat(WindowsEffectiveRights.of(path, "Everyone")).as("Everyone on %s", path).isZero();
        }
        Path jar = home().resolve("lib").resolve(DeployLayout.MCP_JAR);
        assertThat(WindowsEffectiveRights.of(jar, SERVICE) & WindowsEffectiveRights.FILE_READ_DATA).as("the service account reads the jar").isNotZero();
        assertThat(WindowsEffectiveRights.of(jar, SERVICE) & WindowsEffectiveRights.FILE_WRITE_DATA).as("and does not write it").isZero();
        assertThat(WindowsEffectiveRights.of(home().resolve("etc"), SERVICE) & WindowsEffectiveRights.FILE_READ_DATA)
                .as("the service account reads the settings folder").isNotZero();
        assertThat(WindowsEffectiveRights.of(home().resolve("etc"), SERVICE) & WindowsEffectiveRights.FILE_WRITE_DATA)
                .as("the service account does not write the settings folder directly").isZero();
        for (String writable : List.of("etc/driver-state", "etc/update")) {
            assertThat(WindowsEffectiveRights.of(home().resolve(writable), SERVICE) & WindowsEffectiveRights.FILE_WRITE_DATA)
                    .as("the service account writes %s", writable).isNotZero();
        }
    }

    @Test
    @RequiresNonPosixFileSystem
    @DisplayName("WSEC-12 (changed by PCS-035) a re-run that fails after the folders were changed (a jar cannot be replaced: its path is a folder) leaves the ACL of the home and of its folders as it was, so the stopped services can start again, and does not touch the secrets")
    void wsec12_aFailedRerunLeavesTheAclAsItWas(Cli cli) throws IOException {
        assumeTrue(elevated(), "this needs an elevated prompt: only an administrator may close the home to everybody else");
        assertThat(prepareHost(cli).code()).isEmpty();
        Path secrets = home().resolve("etc/secrets");
        Path secret = secrets.resolve("admin").resolve("ledger.admin.token");
        storeSecret(cli, "ledger.admin.token", "never-printed-0123456789");
        Path lib = home().resolve("lib");
        Path jar = lib.resolve(DeployLayout.CLI_JAR);
        Files.delete(jar);
        Files.createDirectories(jar.resolve("blocker"));
        List<Path> paths = List.of(home(), lib, home().resolve("etc"), home().resolve("etc/drivers"), home().resolve("logs"),
                home().resolve("data"), home().resolve("data/attachments"), secrets, secret);
        List<List<AclEntry>> before = acls(paths);

        Outcome rerun = prepareHost(cli);

        assertThat(rerun.code()).contains(ErrorCode.HOST_FAILED);
        assertThat(acls(paths)).as("the ACL of every path").isEqualTo(before);
    }
}
