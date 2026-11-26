// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.cli.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.agentledger.cli.support.RequiresPosixPermissions;

/**
 * ARC-BND-06: {@link HostFiles} inspects directories with {@link LinkOption#NOFOLLOW_LINKS} and refuses symbolic links before
 * changing ownership or delegating POSIX mode changes to {@link org.agentledger.files.FileModes}.
 */
@RequiresPosixPermissions
class HostFilesTest {

    @TempDir
    Path tmp;

    private final HostFiles host = new HostFiles(new DeployFiles());

    @Test
    @DisplayName("ARC-BND-06 directory refuses a symbolic link with FileSystemException and keeps the target directory mode unchanged")
    void directoryRefusesSymbolicLink() throws IOException {
        Path target = Files.createDirectory(tmp.resolve("target"));
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path link = Files.createSymbolicLink(tmp.resolve("link"), target);
        UserPrincipal owner = Files.getOwner(tmp);
        GroupPrincipal group = Files.readAttributes(tmp, PosixFileAttributes.class).group();

        assertThatThrownBy(() -> host.directory(link, "rwx------", owner, group))
                .isInstanceOf(FileSystemException.class);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS)))
                .isEqualTo("rwxr-xr-x");
    }

    @Test
    @DisplayName("ARC-BND-06 restore refuses a symbolic link with FileSystemException and keeps the target directory mode unchanged")
    void restoreRefusesSymbolicLink() throws IOException {
        Path path = Files.createDirectory(tmp.resolve("dir"));
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
        HostFiles.Saved saved = host.save(path).orElseThrow();
        Files.delete(path);

        Path target = Files.createDirectory(tmp.resolve("target"));
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwx------"));
        Files.createSymbolicLink(path, target);

        assertThatThrownBy(() -> host.restore(saved)).isInstanceOf(FileSystemException.class);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS)))
                .isEqualTo("rwx------");
    }
}
