package org.pgledger.cli.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.support.CliExtension;
import org.pgledger.cli.support.CliExtension.Cli;
import org.pgledger.cli.support.CliExtension.Outcome;
import org.pgledger.cli.support.EnglishMessages;
import org.pgledger.cli.support.RecordingOutput;
import org.pgledger.cli.support.RequiresNonPosixFileSystem;

/**
 * WSEC-04 .. WSEC-07 and WSEC-12: the privileges and the take-over of secrets on a real file system with ACLs (Windows), the incident of the
 * test server: a re-run of the installer over secrets that {@code seal-host} handed to the service account. Taking a file over
 * from another owner needs the privileges of an elevated token, so the cases that need them are skipped (an assumption, not a
 * failure) in a prompt that is not elevated; the case that says what happens without them is skipped in an elevated one. The
 * order of the steps is {@link WindowsPlatformTest}, which runs everywhere. Handing a file to the real LocalService account is
 * done as the product does it ({@code seal-host} through the command).
 */
@ExtendWith({ CliExtension.class, EnglishMessages.class })
class WindowsSecretsTest {

    private static final String HOME_OPTION = "--home=C:/ProgramData/pg-ledger-mcp";
    private static final String JAVA = "--java=C:/Program Files/Java/jdk-25/bin/java.exe";

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

    private static UserPrincipal principal(String name) throws IOException {
        return FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByName(name);
    }

    private static UserPrincipal self() throws IOException {
        return principal(ProcessHandle.current().info().user().orElseThrow());
    }

    private Path home() {
        return tmp.resolve("root/C/ProgramData/pg-ledger-mcp");
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

    private Outcome sealHost(Cli cli) {
        return cli.run(tmp.resolve("secrets"), null, new RecordingOutput(), "seal-host", "--root=" + tmp.resolve("root"),
                "--platform=windows", HOME_OPTION, JAVA, "--jdbc-url=jdbc:postgresql://localhost:5432/ledger",
                "--app-account=ledger_app");
    }

    /** The host of an installation that has run seal-host: its secret belongs to LocalService, Administrators see that it exists. */
    private Path sealedInstallation(Cli cli) throws IOException {
        assertThat(prepareHost(cli).code()).isEmpty();
        Path secret = home().resolve("etc/secrets/ledger.admin.token");
        Files.writeString(secret, "never-printed-0123456789");
        assertThat(sealHost(cli).code()).isEmpty();
        assertThat(Files.getOwner(secret)).as("the secret is handed over").isEqualTo(principal(DeployLayout.WINDOWS_SERVICE_ACCOUNT));
        return secret;
    }

    /** Gives the secrets back to the account of the test, so that the temporary directory can be deleted. */
    private void takeEverythingBack() throws IOException {
        Path secrets = home().resolve("etc/secrets");
        if (Files.isDirectory(secrets)) {
            UserPrincipal self = self();
            WindowsAccess access = new WindowsAccess();
            access.reassign(secrets, self, self, WindowsAccess.takenBack(self, true));
            List<Path> files = new ArrayList<>();
            try (Stream<Path> found = Files.list(secrets)) {
                found.forEach(files::add);
            }
            for (Path file : files) {
                access.reassign(file, self, self, WindowsAccess.takenBack(self, false));
            }
        }
        WindowsAccessTest.reopen(tmp);
    }

    private static List<WindowsAccess.Saved> saved(List<Path> paths) throws IOException {
        WindowsAccess access = new WindowsAccess();
        List<WindowsAccess.Saved> all = new ArrayList<>();
        for (Path path : paths) {
            all.add(access.save(path));
        }
        return all;
    }

    @Test
    @RequiresNonPosixFileSystem
    @DisplayName("WSEC-04 in an elevated prompt the token holds SeTakeOwnershipPrivilege and SeRestorePrivilege: enabling them says so, and enabling them again after the first result is closed works (the token is put back)")
    void wsec04_theElevatedTokenHoldsThePrivileges() throws IOException {
        assumeTrue(elevated(), "this needs an elevated prompt: only an administrator's token holds the privileges");

        try (WindowsPrivileges.Enabled first = WindowsPrivileges.enable(WindowsPrivileges.TAKE_OWNERSHIP, WindowsPrivileges.RESTORE)) {
            assertThat(first.allHeld()).isTrue();
        }
        try (WindowsPrivileges.Enabled second = WindowsPrivileges.enable(WindowsPrivileges.TAKE_OWNERSHIP)) {
            assertThat(second.allHeld()).isTrue();
        }
    }

    @Test
    @RequiresNonPosixFileSystem
    @DisplayName("WSEC-05 in a prompt that is not elevated the token does not hold the privileges: enabling them is not an error, it says that they are not all held")
    void wsec05_aTokenWithoutThePrivilegesSaysSo() throws IOException {
        assumeFalse(elevated(), "this needs a prompt that is not elevated");

        try (WindowsPrivileges.Enabled held = WindowsPrivileges.enable(WindowsPrivileges.TAKE_OWNERSHIP, WindowsPrivileges.RESTORE)) {
            assertThat(held.allHeld()).isFalse();
        }
    }

    @Test
    @RequiresNonPosixFileSystem
    @DisplayName("WSEC-06 a file as seal-host leaves it (another owner, Administrators may only see that it exists): the JDK alone is refused (AccessDenied), reassign takes it over and the installer reads it, and hands it over again with the same ACL")
    void wsec06_aSealedFileIsTakenOverAndHandedOverAgain() throws IOException {
        assumeTrue(elevated(), "this needs an elevated prompt: only an administrator may give a file to another account");
        UserPrincipal self = self();
        UserPrincipal system = principal(WindowsAccess.SYSTEM);
        UserPrincipal administrators = principal(WindowsAccess.ADMINISTRATORS);
        Path file = tmp.resolve("sealed.txt");
        Files.writeString(file, "never-printed-0123456789");
        AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class);
        WindowsAccess access = new WindowsAccess();
        try {
            // the order of seal-host: the owner first (the ACL still gives the owner everything), then the ACL
            view.setOwner(system);
            view.setAcl(WindowsAccess.handedOver(system, administrators, false));
            assertThatThrownBy(() -> view.setAcl(WindowsAccess.takenBack(self, false)))
                    .as("without the privileges the installer cannot write the ACL of what the other account owns")
                    .isInstanceOf(AccessDeniedException.class);

            access.reassign(file, self, self, WindowsAccess.takenBack(self, false));

            assertThat(Files.getOwner(file)).isEqualTo(self);
            assertThat(Files.readString(file)).isEqualTo("never-printed-0123456789");
            access.reassign(file, self, system, WindowsAccess.handedOver(system, administrators, false));
            assertThat(Files.getOwner(file)).isEqualTo(system);
            List<AclEntry> acl = view.getAcl();
            assertThat(acl.stream().map(AclEntry::principal)).containsExactly(system, administrators);
        } finally {
            access.reassign(file, self, self, WindowsAccess.takenBack(self, false));
            WindowsAccessTest.reopen(tmp);
        }
    }

    @Test
    @RequiresNonPosixFileSystem
    @DisplayName("WSEC-07 the incident: prepare-host run again over an installation whose secrets seal-host handed to LocalService succeeds (it was AccessDenied on the secrets folder), and the secrets are the installer's alone, readable again")
    void wsec07_aRerunOverSealedSecrets(Cli cli) throws IOException {
        assumeTrue(elevated(), "this needs an elevated prompt: only an administrator may close a folder and take a secret over");
        try {
            Path secret = sealedInstallation(cli);

            Outcome rerun = prepareHost(cli);

            assertThat(rerun.code()).as("the re-run").isEmpty();
            UserPrincipal self = self();
            for (Path path : List.of(secret, secret.getParent())) {
                assertThat(Files.getOwner(path)).as(path.toString()).isEqualTo(self);
                assertThat(Files.getFileAttributeView(path, AclFileAttributeView.class).getAcl()).as(path.toString()).hasSize(1);
            }
            assertThat(Files.readString(secret)).isEqualTo("never-printed-0123456789");
        } finally {
            takeEverythingBack();
        }
    }

    @Test
    @RequiresNonPosixFileSystem
    @DisplayName("WSEC-12 a re-run that fails after the folders were closed (a jar cannot be replaced: its path is a folder) leaves the home, its folders and the sealed secrets as they were: the same owner and ACL of each, so the stopped services can start again")
    void wsec12_aFailedRerunLeavesTheHostAsItWas(Cli cli) throws IOException {
        assumeTrue(elevated(), "this needs an elevated prompt: only an administrator may close a folder and take a secret over");
        try {
            Path secret = sealedInstallation(cli);
            Path lib = home().resolve("lib");
            Path jar = lib.resolve(DeployLayout.CLI_JAR);
            Files.delete(jar);
            Files.createDirectories(jar.resolve("blocker"));
            List<Path> paths = List.of(home(), lib, home().resolve("etc"), home().resolve("etc/drivers"), home().resolve("logs"),
                    secret.getParent(), secret);
            List<WindowsAccess.Saved> before = saved(paths);

            Outcome rerun = prepareHost(cli);

            assertThat(rerun.code()).contains(ErrorCode.HOST_FAILED);
            assertThat(saved(paths)).as("owner and ACL of every path").isEqualTo(before);
        } finally {
            takeEverythingBack();
        }
    }
}
