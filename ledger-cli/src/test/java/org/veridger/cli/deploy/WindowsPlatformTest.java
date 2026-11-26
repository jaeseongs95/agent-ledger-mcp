// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.veridger.cli.secret.SecretFolder;
import org.veridger.cli.support.RecordingHostAccounts;
import org.veridger.files.ServicePrincipalAcl;
import org.veridger.files.WellKnownAccount;

/**
 * PCS-034, PCS-035 (and the put-back of WSEC-10, WSEC-11): the steps of {@code prepare-host} on Windows ({@link WindowsPlatform}) and
 * what a failed run leaves behind. There is no {@code seal-host} and no hand-over of the secrets: the folders are made with the
 * access of the service account in the same step, and the secret folders with the installing user and the service account (read).
 * The platform is run on any operating system: what touches the ACLs ({@link WindowsAccess}) and the copy of the jars
 * ({@link HostFiles}) are replaced by recorders, the folders are real ones below a temporary root (a Windows path becomes a path
 * below the root, {@link WindowsPlatform#hostPath}). The real ACLs are {@link WindowsSecretsTest} and the Windows test server.
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
    private final DeployLayout layout = DeployLayout.windows("veridger", "C:/ProgramData/veridger");
    private WindowsPlatform platform;
    private Path home;
    private Path secrets;
    /** The folders of the uses below the secrets (SPL-005). */
    private List<Path> folders;
    /** The folder of the attachment keys: beside the secrets, in no configuration tree (SPL-004). */
    private Path keys;

    @BeforeEach
    void setUp() throws IOException {
        RecordingHostAccounts accounts = new RecordingHostAccounts(Map.of(), Map.of(), Map.of(WellKnownAccount.SYSTEM, SYSTEM,
                WellKnownAccount.ADMINISTRATORS, ADMINISTRATORS, WellKnownAccount.SERVICE, SERVICE));
        platform = new WindowsPlatform(accounts, fileSystem -> INSTALLER, access, host, new DeployFiles());
        home = tmp.resolve("C/ProgramData/veridger");
        secrets = home.resolve("etc/secrets");
        folders = SecretFolder.all().stream().map(folder -> secrets.resolve(folder.directory())).toList();
        keys = home.resolve("etc/attachment-keys");
        Files.writeString(tmp.resolve("WinSW.exe"), "winsw");
        // what a read of a path finds: a record the test can recognise
        when(access.save(any())).thenAnswer(call -> new WindowsAccess.Saved(call.getArgument(0), List.of()));
    }

    /** The host of an earlier installation: every folder, the folders of the secrets with two secrets, the folder of the keys. */
    private void installed() throws IOException {
        for (String folder : List.of("lib", "etc", "etc/drivers", "logs", "data", "data/attachments", "etc/attachment-keys")) {
            Files.createDirectories(home.resolve(folder));
        }
        for (Path folder : folders) {
            Files.createDirectories(folder);
        }
        Files.writeString(secrets.resolve("admin").resolve("ledger.admin.token"), "never-printed-1");
        Files.writeString(secrets.resolve("common").resolve("ledger.jdbc.password"), "never-printed-2");
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

    private List<AclEntry> closedWith(Set<AclEntryPermission> rights) {
        return WindowsAccess.closedWith(SYSTEM, ADMINISTRATORS, SERVICE, rights);
    }

    @Test
    @DisplayName("PCS-034 a first installation: the home is closed to every account but SYSTEM and Administrators, each folder is closed with the entry of the service account (jars: read and run, settings, logs, data and attachments: modify), and the secret directory, its four folders of the uses (SPL-005) and the folder of the attachment keys beside it are made with the installing user (everything) and the service account (read), nothing is read, nothing is changed in owner")
    void pcs034_aFirstInstallation() throws IOException {
        prepare();

        InOrder order = inOrder(access);
        order.verify(access).set(home, WindowsAccess.closed(SYSTEM, ADMINISTRATORS));
        order.verify(access).set(home.resolve("lib"), closedWith(WindowsAccess.READ_EXECUTE));
        order.verify(access).set(home.resolve("etc"), closedWith(WindowsAccess.MODIFY));
        // the services write nothing in the driver folder (the registration file is in etc): read and run, like the jars (item 8 of the review)
        order.verify(access).set(home.resolve("etc/drivers"), closedWith(WindowsAccess.READ_EXECUTE));
        order.verify(access).set(home.resolve("logs"), closedWith(WindowsAccess.MODIFY));
        order.verify(access).set(home.resolve("data"), closedWith(WindowsAccess.MODIFY));
        order.verify(access).set(home.resolve("data/attachments"), closedWith(WindowsAccess.MODIFY));
        List<AclEntry> forSecrets = ServicePrincipalAcl.ownerAndService(INSTALLER, SERVICE, true);
        order.verify(access).set(secrets, forSecrets);
        for (Path folder : folders) {
            order.verify(access).set(folder, forSecrets);
        }
        order.verify(access).set(keys, forSecrets);
        verify(access, never()).save(any());
        verify(access, never()).restore(any());
        assertThat(forSecrets).hasSize(2);
        assertThat(folders).extracting(path -> path.getFileName().toString()).containsExactly("common", "admin", "mcp", "tool");
        assertThat(secrets).isDirectory();
        assertThat(folders).allSatisfy(folder -> assertThat(folder).isDirectory());
        assertThat(keys).isDirectory();
        assertThat(keys.startsWith(secrets)).as("the key folder is in no folder of the secrets (a configuration tree)").isFalse();
        assertThat(Files.readString(home.resolve("lib/veridger.exe"))).isEqualTo("winsw");
        assertThat(Files.readString(home.resolve("lib/veridger-admin.exe"))).isEqualTo("winsw");
    }

    @Test
    @DisplayName("PCS-035 a re-run over an installation that has its secrets: the folders get their ACLs again, the secrets folder, the folder of the keys and the secrets are not read, changed or judged, nothing fails (the earlier design handed them to the service account, and a re-run was refused)")
    void pcs035_aRerunLeavesTheSecretsAlone() throws IOException {
        installed();

        prepare();

        for (String folder : List.of("", "lib", "etc", "etc/drivers", "logs", "data", "data/attachments")) {
            verify(access).set(eq(home.resolve(folder)), any());
        }
        verify(access, never()).set(eq(secrets), any());
        verify(access, never()).set(eq(keys), any());
        verify(access, never()).save(eq(secrets));
        verify(access, never()).save(eq(keys));
        for (Path folder : folders) {
            verify(access, never()).set(eq(folder), any());
            verify(access, never()).save(eq(folder));
        }
        verify(access, never()).restore(any());
        assertThat(Files.readString(secrets.resolve("admin").resolve("ledger.admin.token"))).isEqualTo("never-printed-1");
        assertThat(Files.readString(home.resolve("lib/veridger.exe"))).isEqualTo("winsw");
    }

    @Test
    @DisplayName("PCS-034 / SPL-005 only the folders that are missing are made when the secrets folder exists without them (an installation made before the folders of the uses and before the attachments)")
    void pcs034_theMissingFoldersAloneAreMade() throws IOException {
        Files.createDirectories(secrets);
        Files.createDirectories(secrets.resolve("admin"));

        prepare();

        List<AclEntry> forSecrets = ServicePrincipalAcl.ownerAndService(INSTALLER, SERVICE, true);
        verify(access, never()).set(eq(secrets), any());
        verify(access, never()).set(eq(secrets.resolve("admin")), any());
        verify(access).set(secrets.resolve("common"), forSecrets);
        verify(access).set(secrets.resolve("mcp"), forSecrets);
        verify(access).set(secrets.resolve("tool"), forSecrets);
        verify(access).set(keys, forSecrets);
    }

    @Test
    @DisplayName("WSEC-10 (changed by PCS) a failure after the folders were closed (the copy of the jars) puts the ACL back of every folder that was read, the last one first; the failure of the run is the one that is reported")
    void wsec10_aLaterFailurePutsEverythingBack() throws IOException {
        installed();
        IOException full = new IOException("disk full");
        doThrow(full).when(host).copyProduct(any(), any(), any(), any());

        assertThatThrownBy(this::prepare).isSameAs(full);

        assertThat(restored(7)).as("the seven folders that exist, in reverse").containsExactly(home.resolve("data/attachments"),
                home.resolve("data"), home.resolve("logs"), home.resolve("etc/drivers"), home.resolve("etc"), home.resolve("lib"), home);
    }

    @Test
    @DisplayName("WSEC-11 (changed by PCS) a path that cannot be put back does not stop the others and is attached to the failure")
    void wsec11_aPathThatCannotBePutBackIsAttached() throws IOException {
        installed();
        IOException full = new IOException("disk full");
        doThrow(full).when(host).copyProduct(any(), any(), any(), any());
        IOException stuck = new IOException("cannot restore");
        doThrow(stuck).when(access).restore(argThat(saved -> saved != null && Objects.equals(saved.path(), home.resolve("etc"))));

        assertThatThrownBy(this::prepare).isSameAs(full).hasSuppressedException(stuck);

        assertThat(restored(7)).as("all seven, although one failed").hasSize(7).endsWith(home.resolve("lib"), home);
    }

    @Test
    @DisplayName("PCS-037 WindowsAccess has no operation that changes an owner or takes a privilege: no reassign, no grantTree, no setOwner")
    void pcs037_noChangeOfOwner() {
        assertThat(Arrays.stream(WindowsAccess.class.getDeclaredMethods()).map(method -> method.getName()).toList())
                .doesNotContain("reassign", "grantTree", "grant", "setOwner", "enable");
    }
}
