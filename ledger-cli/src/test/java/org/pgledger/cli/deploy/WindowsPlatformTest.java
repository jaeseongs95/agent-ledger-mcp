package org.pgledger.cli.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.pgledger.cli.support.RecordingHostAccounts;

/**
 * WSEC-01 .. WSEC-03 (and WSEC-08 .. WSEC-11): the order of the steps of {@code prepare-host} and {@code seal-host} on Windows
 * ({@link WindowsPlatform}), and what a failed run leaves behind. The platform is run on any operating system: what touches the
 * ACLs ({@link WindowsAccess}) and the copy of the jars ({@link HostFiles}) are replaced by recorders, the folders are real ones
 * below a temporary root (a Windows path becomes a path below the root, {@link DeployFiles#resolve}). The real ACLs, the
 * privileges and the services are {@link WindowsSecretsTest} and the Windows test server.
 */
class WindowsPlatformTest {

    private record Principal(String name) implements UserPrincipal {

        @Override
        public String getName() {
            return name;
        }
    }

    private static final UserPrincipal SYSTEM = new Principal("system");
    private static final UserPrincipal ADMINISTRATORS = new Principal("administrators");
    private static final UserPrincipal SERVICE = new Principal("service");
    private static final UserPrincipal INSTALLER = new Principal("installer");

    @TempDir
    Path tmp;

    private final WindowsAccess access = mock(WindowsAccess.class);
    private final HostFiles host = mock(HostFiles.class);
    private final DeployLayout layout = DeployLayout.windows("pg-ledger-mcp", "C:/ProgramData/pg-ledger-mcp");
    private WindowsPlatform platform;
    private Path home;
    private Path secrets;
    private final List<AclEntry> closed = WindowsAccess.closed(SYSTEM, ADMINISTRATORS);

    @BeforeEach
    void setUp() throws IOException {
        RecordingHostAccounts accounts = new RecordingHostAccounts(Map.of(WindowsAccess.SYSTEM, SYSTEM,
                WindowsAccess.ADMINISTRATORS, ADMINISTRATORS, DeployLayout.WINDOWS_SERVICE_ACCOUNT, SERVICE), Map.of());
        platform = new WindowsPlatform(accounts, fileSystem -> INSTALLER, access, host, new DeployFiles());
        home = tmp.resolve("C/ProgramData/pg-ledger-mcp");
        secrets = home.resolve("etc/secrets");
        Files.writeString(tmp.resolve("WinSW.exe"), "winsw");
    }

    /** The host of an earlier installation, sealed: every folder, and two secrets in the secrets folder (in name order). */
    private List<Path> installed() throws IOException {
        for (String folder : List.of("lib", "etc", "etc/drivers", "logs", "etc/secrets")) {
            Files.createDirectories(home.resolve(folder));
        }
        Files.writeString(secrets.resolve("ledger.admin.token"), "never-printed-1");
        Files.writeString(secrets.resolve("ledger.jdbc.password"), "never-printed-2");
        return List.of(secrets.resolve("ledger.admin.token"), secrets.resolve("ledger.jdbc.password"));
    }

    private void prepare() throws IOException {
        platform.prepare(layout, tmp, tmp.resolve("jars"), tmp.resolve("driver.jar"), List.of(tmp.resolve("WinSW.exe")));
    }

    @Test
    @DisplayName("WSEC-01 seal-host takes the secrets folder first (a sealed one cannot even be listed by an administrator), hands over each file and then the folder, after the grants of the other folders, which skip the secrets folder; the folder is the service account's last")
    void wsec01_sealTakesTheFolderHandsOverTheFilesAndThenTheFolder() throws IOException {
        List<Path> files = installed();

        platform.seal(layout, tmp);

        InOrder order = inOrder(access);
        order.verify(access).grantTree(home.resolve("lib"), SERVICE, WindowsAccess.READ_EXECUTE, secrets);
        order.verify(access).grantTree(home.resolve("etc"), SERVICE, WindowsAccess.MODIFY, secrets);
        order.verify(access).grantTree(home.resolve("logs"), SERVICE, WindowsAccess.MODIFY, secrets);
        order.verify(access).reassign(secrets, INSTALLER, INSTALLER, WindowsAccess.takenBack(INSTALLER, true));
        for (Path file : files) {
            order.verify(access).reassign(file, INSTALLER, SERVICE, WindowsAccess.handedOver(SERVICE, ADMINISTRATORS, false));
        }
        order.verify(access).reassign(secrets, INSTALLER, SERVICE, WindowsAccess.handedOver(SERVICE, ADMINISTRATORS, true));
        order.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("WSEC-02 prepare-host over the secrets of an earlier run: the secrets folder and each file in it are taken over by the installer (owner and ACL, ownership first), the secrets folder is never closed like the others, and the other folders are closed as before")
    void wsec02_prepareTakesTheSecretsOver() throws IOException {
        List<Path> files = installed();

        prepare();

        InOrder order = inOrder(access);
        order.verify(access).reassign(eq(secrets), eq(INSTALLER), eq(INSTALLER), eq(WindowsAccess.takenBack(INSTALLER, true)));
        for (Path file : files) {
            order.verify(access).reassign(eq(file), eq(INSTALLER), eq(INSTALLER), eq(WindowsAccess.takenBack(INSTALLER, false)));
        }
        verify(access, never()).set(eq(secrets), any(), any());
        for (String folder : List.of("", "lib", "etc", "etc/drivers", "logs")) {
            verify(access).set(eq(home.resolve(folder)), isNull(), eq(closed));
        }
        assertThat(Files.readString(home.resolve("lib/pg-ledger-mcp.exe"))).isEqualTo("winsw");
        assertThat(Files.readString(home.resolve("lib/pg-ledger-mcp-admin.exe"))).isEqualTo("winsw");
    }

    @Test
    @DisplayName("WSEC-03 a privilege that the process does not hold is a failure of the host naming the path and the kind (HOST_FAILED, whose text says to run in an elevated prompt), not a raw AccessDeniedException")
    void wsec03_aMissingPrivilegeIsAFailureOfTheHost() {
        AccessDeniedException refusal = new AccessDeniedException("x");

        FailureException failure = HostFiles.failure(new PrivilegeNotHeldException(secrets, refusal));

        assertThat(failure.code()).isEqualTo(ErrorCode.HOST_FAILED);
        assertThat(failure.arguments()).containsExactly(secrets.toString(), "PrivilegeNotHeldException");
        assertThat(failure).hasRootCause(refusal);
    }
}
