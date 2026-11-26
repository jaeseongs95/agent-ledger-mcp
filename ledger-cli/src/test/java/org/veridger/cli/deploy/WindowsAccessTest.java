// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.files.ServicePrincipalAcl;

/**
 * INS-045 (changed by PCS-034): the access of a Windows installation, in Java and not with {@code icacls} ({@link WindowsAccess}): the
 * entries it builds, with principals of the test, on any file system. There is no hand-over of the secrets and no
 * {@code seal-host} any more, so the entries of a hand-over, of taking back, of a grant to a whole tree and the privileges are gone;
 * the real ACLs are {@link WindowsSecretsTest}.
 */
class WindowsAccessTest {

    private record Principal(String name) implements UserPrincipal {

        @Override
        public String getName() {
            return name;
        }
    }

    private static final UserPrincipal SERVICE = new Principal("service");
    private static final UserPrincipal ADMINISTRATORS = new Principal("administrators");
    private static final UserPrincipal SYSTEM = new Principal("system");
    private static final UserPrincipal INSTALLER = new Principal("installer");

    @Test
    @DisplayName("INS-045 the home is closed to every account but SYSTEM and Administrators: two ALLOW entries with everything, inherited by files and folders")
    void ins045_closedHome() {
        List<AclEntry> acl = WindowsAccess.closed(SYSTEM, ADMINISTRATORS);

        assertThat(acl).hasSize(2);
        assertThat(acl).allSatisfy(entry -> {
            assertThat(entry.type()).isEqualTo(AclEntryType.ALLOW);
            assertThat(entry.permissions()).isEqualTo(WindowsAccess.FULL);
            assertThat(entry.flags()).containsExactlyInAnyOrder(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
        });
        assertThat(acl.stream().map(AclEntry::principal)).containsExactly(SYSTEM, ADMINISTRATORS);
    }

    @Test
    @DisplayName("PCS-034 a folder of the installation is the closed home plus the service account with the given rights, all inherited: the service account is the third entry and the others are those of the home")
    void pcs034_aFolderWithTheServiceAccount() {
        List<AclEntry> acl = WindowsAccess.closedWith(SYSTEM, ADMINISTRATORS, SERVICE, WindowsAccess.MODIFY);

        assertThat(acl).hasSize(3);
        assertThat(acl.subList(0, 2)).isEqualTo(WindowsAccess.closed(SYSTEM, ADMINISTRATORS));
        assertThat(acl.get(2).principal()).isEqualTo(SERVICE);
        assertThat(acl.get(2).type()).isEqualTo(AclEntryType.ALLOW);
        assertThat(acl.get(2).permissions()).isEqualTo(WindowsAccess.MODIFY);
        assertThat(acl.get(2).flags()).containsExactlyInAnyOrder(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
    }

    @Test
    @DisplayName("PCS-034 the secret folders: the installing user with everything and the service account with read and traverse, both inherited, and no entry for anyone else (Administrators and SYSTEM included)")
    void pcs034_theSecretFolders() {
        List<AclEntry> acl = ServicePrincipalAcl.ownerAndService(INSTALLER, SERVICE, true);

        assertThat(acl).hasSize(2);
        assertThat(acl.get(0).principal()).isEqualTo(INSTALLER);
        assertThat(acl.get(0).permissions()).isEqualTo(WindowsAccess.FULL);
        assertThat(acl.get(1).principal()).isEqualTo(SERVICE);
        assertThat(acl.get(1).permissions()).isEqualTo(ServicePrincipalAcl.READ_FOLDER)
                .doesNotContain(AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA, AclEntryPermission.DELETE,
                        AclEntryPermission.DELETE_CHILD, AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER);
        assertThat(acl).allSatisfy(entry -> {
            assertThat(entry.type()).isEqualTo(AclEntryType.ALLOW);
            assertThat(entry.flags()).containsExactlyInAnyOrder(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
        });
        assertThat(acl.stream().map(AclEntry::principal)).doesNotContain(SYSTEM, ADMINISTRATORS);
    }

    @Test
    @DisplayName("PCS-030 a secret file: the installing user with everything and the service account with read (no traverse), inherited by nothing; when the owner is the service account itself there is one entry")
    void pcs030_aSecretFile() {
        List<AclEntry> acl = ServicePrincipalAcl.ownerAndService(INSTALLER, SERVICE, false);

        assertThat(acl).hasSize(2).allSatisfy(entry -> assertThat(entry.flags()).isEmpty());
        assertThat(acl.get(1).permissions()).isEqualTo(ServicePrincipalAcl.READ_FILE).doesNotContain(AclEntryPermission.EXECUTE);
        assertThat(ServicePrincipalAcl.ownerAndService(SERVICE, SERVICE, false)).hasSize(1);
    }

    @Test
    @DisplayName("INS-045 the rights of the service account on the jars and on the settings: read and run, and modify without changing the ACL or the owner")
    void ins045_rights() {
        assertThat(WindowsAccess.READ_EXECUTE).contains(AclEntryPermission.READ_DATA, AclEntryPermission.EXECUTE)
                .doesNotContain(AclEntryPermission.WRITE_DATA, AclEntryPermission.DELETE);
        assertThat(WindowsAccess.MODIFY).contains(AclEntryPermission.WRITE_DATA, AclEntryPermission.DELETE,
                AclEntryPermission.READ_DATA).doesNotContain(AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER);
    }
}
