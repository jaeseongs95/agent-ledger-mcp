// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.files;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.attribute.UserPrincipal;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * R9-D0091, R9-D0092: the kind of the file system is decided in one place, and the choice of the protection is made from it. A file
 * system of each kind is made of the one the test runs on ({@link FileSystemWithViews}), so both kinds are tested on every host.
 */
class FileKindTest {

    private static final FileSystem HOST = FileSystems.getDefault();

    @Test
    @DisplayName("R9-D0091 the kind is POSIX with the posix view (also when the file system has the dos view), ACL with the acl view and no posix view, otherwise OTHER")
    void theKindFollowsTheViews() {
        assertThat(FileKind.of(new FileSystemWithViews(HOST, FileSystemWithViews.LINUX))).isEqualTo(FileKind.POSIX);
        assertThat(FileKind.of(new FileSystemWithViews(HOST, FileSystemWithViews.WINDOWS))).isEqualTo(FileKind.ACL);
        assertThat(FileKind.of(new FileSystemWithViews(HOST, FileSystemWithViews.NEITHER))).isEqualTo(FileKind.OTHER);
    }

    @Test
    @DisplayName("R9-D0091 select gives Posix on a POSIX file system without asking for an account, the Acl that the caller makes on an ACL file system, and nothing on another")
    void selectChoosesByTheKind() throws IOException {
        AtomicInteger asked = new AtomicInteger();
        FileProtection.AclChoice acl = fileSystem -> {
            asked.incrementAndGet();
            return new FileProtection.Acl(null);
        };

        Optional<FileProtection> linux = FileProtection.select(new FileSystemWithViews(HOST, FileSystemWithViews.LINUX), acl);
        assertThat(linux).containsInstanceOf(FileProtection.Posix.class);
        assertThat(asked).as("no account is looked up for POSIX permissions").hasValue(0);

        Optional<FileProtection> windows = FileProtection.select(new FileSystemWithViews(HOST, FileSystemWithViews.WINDOWS), acl);
        assertThat(windows).containsInstanceOf(FileProtection.Acl.class);
        assertThat(asked).hasValue(1);

        assertThat(FileProtection.select(new FileSystemWithViews(HOST, FileSystemWithViews.NEITHER), acl)).isEmpty();
        assertThat(asked).as("and none for a file system with neither").hasValue(1);
    }

    @Test
    @DisplayName("R9-D0095 c: the account of the process is the one the JDK names for the process, with the lookup of the file system turning it into a principal that equals the owner of a file")
    void theAccountOfTheProcessIsTheOneOfProcessHandle() throws IOException {
        String name = ProcessAccount.name().orElseThrow();
        assertThat(name).isEqualTo(ProcessHandle.current().info().user().orElseThrow());

        UserPrincipal principal = ProcessAccount.principal(HOST);
        assertThat(principal).isEqualTo(HOST.getUserPrincipalLookupService().lookupPrincipalByName(name));
        assertThat(principal.getName()).isNotBlank();
    }
}
