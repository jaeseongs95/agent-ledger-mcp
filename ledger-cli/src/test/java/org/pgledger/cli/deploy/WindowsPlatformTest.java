package org.pgledger.cli.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.pgledger.cli.support.RecordingHostAccounts;

/**
 * WSEC-01 .. WSEC-03 (and WSEC-08 .. WSEC-11): the order of the steps of {@code prepare-host} and {@code seal-host} on Windows
 * ({@link WindowsPlatform}), and what a failed run leaves behind. The platform is run on any operating system: what touches the
 * ACLs ({@link WindowsAccess}) and the copy of the jars ({@link HostFiles}) are replaced by recorders, the folders are real ones
 * below a temporary root (a Windows path becomes a path below the root, {@link WindowsPlatform#hostPath}). The real ACLs, the
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
    private static final UserPrincipal BEFORE = new Principal("before");

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
        // what a read of a path finds: a record the test can recognise
        when(access.save(any())).thenAnswer(call -> new WindowsAccess.Saved(call.getArgument(0), BEFORE, List.of()));
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

    /** The paths that were put back, in the order they were, after checking that it was this many. */
    private List<Path> restored(int count) throws IOException {
        ArgumentCaptor<WindowsAccess.Saved> restored = ArgumentCaptor.forClass(WindowsAccess.Saved.class);
        verify(access, times(count)).restore(restored.capture());
        return restored.getAllValues().stream().map(WindowsAccess.Saved::path).toList();
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
        // ATA-036: the data folder and the attachment folder get the entry of the service account on the folder itself
        order.verify(access).grant(home.resolve("data"), SERVICE, WindowsAccess.MODIFY);
        order.verify(access).grant(home.resolve("data/attachments"), SERVICE, WindowsAccess.MODIFY);
        order.verify(access).reassign(secrets, INSTALLER, INSTALLER, WindowsAccess.takenBack(INSTALLER, true));
        for (Path file : files) {
            order.verify(access).reassign(file, INSTALLER, SERVICE, WindowsAccess.handedOver(SERVICE, ADMINISTRATORS, false));
        }
        order.verify(access).reassign(secrets, INSTALLER, SERVICE, WindowsAccess.handedOver(SERVICE, ADMINISTRATORS, true));
        order.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("ATA-036 seal-host takes the folder of the attachment keys inside the secrets folder, hands over its files with the others, hands the folder over before the secrets folder, and gives the service account its entry on the data folder and the attachment folder only (never on what the service made there)")
    void ata036_sealHandsOverTheKeyFolder() throws IOException {
        List<Path> tokens = installed();
        Path keys = secrets.resolve("attachment-keys");
        Files.createDirectories(keys);
        Path key = keys.resolve("key-0123456789abcdef");
        Files.writeString(key, "never-printed-3");
        List<Path> files = new ArrayList<>(tokens);
        files.add(key);
        files.sort(Comparator.naturalOrder());

        platform.seal(layout, tmp);

        InOrder order = inOrder(access);
        order.verify(access).grant(home.resolve("data"), SERVICE, WindowsAccess.MODIFY);
        order.verify(access).grant(home.resolve("data/attachments"), SERVICE, WindowsAccess.MODIFY);
        order.verify(access).reassign(secrets, INSTALLER, INSTALLER, WindowsAccess.takenBack(INSTALLER, true));
        order.verify(access).reassign(keys, INSTALLER, INSTALLER, WindowsAccess.takenBack(INSTALLER, true));
        for (Path file : files) {
            order.verify(access).reassign(file, INSTALLER, SERVICE, WindowsAccess.handedOver(SERVICE, ADMINISTRATORS, false));
        }
        order.verify(access).reassign(keys, INSTALLER, SERVICE, WindowsAccess.handedOver(SERVICE, ADMINISTRATORS, true));
        order.verify(access).reassign(secrets, INSTALLER, SERVICE, WindowsAccess.handedOver(SERVICE, ADMINISTRATORS, true));
        verify(access, never()).grantTree(eq(home.resolve("data")), any(), any(), any());
        verify(access, never()).grantTree(eq(home.resolve("data/attachments")), any(), any(), any());
    }

    @Test
    @DisplayName("ATA-036 a first installation: the data folder and the attachment folder are closed like the other folders, and the folder of the keys is made inside the secrets as the installer's alone, after the secrets folder; nothing is read")
    void ata036_aFirstInstallationMakesTheAttachmentFolders() throws IOException {
        prepare();

        InOrder order = inOrder(access);
        for (String folder : List.of("", "lib", "etc", "etc/drivers", "logs", "data", "data/attachments")) {
            order.verify(access).set(eq(home.resolve(folder)), isNull(), eq(closed));
        }
        order.verify(access).reassign(eq(secrets), eq(INSTALLER), eq(INSTALLER), eq(WindowsAccess.takenBack(INSTALLER, true)));
        order.verify(access).reassign(eq(secrets.resolve("attachment-keys")), eq(INSTALLER), eq(INSTALLER),
                eq(WindowsAccess.takenBack(INSTALLER, true)));
        verify(access, never()).save(any());
        assertThat(home.resolve("data/attachments")).isDirectory();
        assertThat(secrets.resolve("attachment-keys")).isDirectory();
    }

    @Test
    @DisplayName("ATA-036 prepare-host over an earlier run that has keys: the folder of the keys and its files are taken back by the installer with the other secrets, before anything is closed")
    void ata036_aRerunTakesTheKeyFolderBack() throws IOException {
        List<Path> tokens = installed();
        Path keys = secrets.resolve("attachment-keys");
        Files.createDirectories(keys);
        Path key = keys.resolve("key-0123456789abcdef");
        Files.writeString(key, "never-printed-3");
        List<Path> files = new ArrayList<>(tokens);
        files.add(key);
        files.sort(Comparator.naturalOrder());

        prepare();

        InOrder order = inOrder(access);
        order.verify(access).reassign(eq(secrets), eq(INSTALLER), eq(INSTALLER), eq(WindowsAccess.takenBack(INSTALLER, true)));
        order.verify(access).reassign(eq(keys), eq(INSTALLER), eq(INSTALLER), eq(WindowsAccess.takenBack(INSTALLER, true)));
        for (Path file : files) {
            order.verify(access).reassign(eq(file), eq(INSTALLER), eq(INSTALLER), eq(WindowsAccess.takenBack(INSTALLER, false)));
        }
        order.verify(access).set(eq(home), isNull(), eq(closed));
        verify(access, never()).set(eq(keys), any(), any());
        verify(access, never()).restore(any());
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

    @Test
    @DisplayName("WSEC-08 a re-run over the secrets of an earlier run: prepare-host takes the secrets back first (the folder, then each file), and only then closes the home and its folders, in order")
    void wsec08_aRerunTakesTheSecretsBackBeforeItClosesAnything() throws IOException {
        List<Path> files = installed();

        prepare();

        InOrder order = inOrder(access);
        order.verify(access).reassign(eq(secrets), eq(INSTALLER), eq(INSTALLER), eq(WindowsAccess.takenBack(INSTALLER, true)));
        for (Path file : files) {
            order.verify(access).reassign(eq(file), eq(INSTALLER), eq(INSTALLER), eq(WindowsAccess.takenBack(INSTALLER, false)));
        }
        for (String folder : List.of("", "lib", "etc", "etc/drivers", "logs")) {
            order.verify(access).set(eq(home.resolve(folder)), isNull(), eq(closed));
        }
        verify(access, never()).restore(any());
    }

    @Test
    @DisplayName("WSEC-09 a first installation (nothing exists): no path is read, the home and its folders are closed in order, and the secrets folder is made last and is the installer's alone")
    void wsec09_aFirstInstallationClosesAndThenMakesTheSecrets() throws IOException {
        prepare();

        InOrder order = inOrder(access);
        for (String folder : List.of("", "lib", "etc", "etc/drivers", "logs")) {
            order.verify(access).set(eq(home.resolve(folder)), isNull(), eq(closed));
        }
        order.verify(access).reassign(eq(secrets), eq(INSTALLER), eq(INSTALLER), eq(WindowsAccess.takenBack(INSTALLER, true)));
        verify(access, never()).save(any());
        verify(access, never()).restore(any());
        assertThat(secrets).isDirectory();
    }

    @Test
    @DisplayName("WSEC-10 the file system refuses to give back a secret (the incident: AccessDenied): nothing is closed, nothing is copied, and every path that was read is put back, the last one first; the refusal is the failure that is reported")
    void wsec10_aRefusedTakeBackChangesNothingElse() throws IOException {
        List<Path> files = installed();
        AccessDeniedException refusal = new AccessDeniedException(files.get(1).toString());
        doThrow(refusal).when(access).reassign(eq(files.get(1)), any(), any(), any());

        assertThatThrownBy(this::prepare).isSameAs(refusal);

        verify(access, never()).set(any(), any(), any());
        verify(host, never()).copyProduct(any(), any(), any(), any());
        assertThat(home.resolve("lib/pg-ledger-mcp.exe")).doesNotExist();
        assertThat(restored(8)).as("six folders and the two files that were read, in reverse").containsExactly(files.get(1),
                files.get(0), home.resolve("logs"), secrets, home.resolve("etc/drivers"), home.resolve("etc"),
                home.resolve("lib"), home);
    }

    @Test
    @DisplayName("WSEC-11 a failure after the folders were closed (the copy of the jars) puts back everything that was read; a path that cannot be put back does not stop the others and is attached to the failure")
    void wsec11_aLaterFailurePutsEverythingBack() throws IOException {
        List<Path> files = installed();
        IOException full = new IOException("disk full");
        doThrow(full).when(host).copyProduct(any(), any(), any(), any());
        IOException stuck = new IOException("cannot restore");
        doThrow(stuck).when(access).restore(argThat(saved -> saved != null && Objects.equals(saved.path(), secrets)));

        assertThatThrownBy(this::prepare).isSameAs(full).hasSuppressedException(stuck);

        verify(access).set(eq(home), isNull(), eq(closed));
        assertThat(restored(8)).as("all eight, although one failed").containsExactly(files.get(1), files.get(0),
                home.resolve("logs"), secrets, home.resolve("etc/drivers"), home.resolve("etc"), home.resolve("lib"), home);
    }
}
