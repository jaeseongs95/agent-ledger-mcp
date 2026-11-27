// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli.deploy;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.veridger.cli.ErrorCode;
import org.veridger.cli.FailureException;
import org.veridger.files.FileModes;
import org.springframework.stereotype.Component;

/**
 * What the host platforms ({@link ServicePlatform}) do to the files of the host in common: make a directory with its owner and
 * mode, copy the jars of the product, check what the file system supports, and report a failure of the host. A component,
 * because it changes the file system; the report of a failure ({@link #failure}) is a pure function.
 */
@Component
final class HostFiles {

    private final DeployFiles files;

    HostFiles(DeployFiles files) {
        this.files = files;
    }

    /** Creates the directory when it is not there and gives it the owner and the mode, as {@code install -d -m -o -g} did. */
    void directory(Path path, String mode, UserPrincipal owner, GroupPrincipal group) throws IOException {
        Files.createDirectories(path);
        PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view.readAttributes().isSymbolicLink()) {
            throw new FileSystemException(path.toString(), null, "symbolic link");
        }
        if (owner != null) {
            view.setOwner(owner);
        }
        if (group != null) {
            view.setGroup(group);
        }
        FileModes.apply(path, mode);
    }

    /** The owner, the group and the mode of a directory as they were before a run changed them (POSIX file systems). */
    record Saved(Path path, UserPrincipal owner, GroupPrincipal group, Set<PosixFilePermission> mode) {
    }

    /**
     * Reads the owner, the group and the mode of {@code path} when it exists (nothing is changed by reading), so that a run that
     * fails can put them back with {@link #restore}; empty when there is no such directory.
     */
    Optional<Saved> save(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        PosixFileAttributes attributes = Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return Optional.of(new Saved(path, attributes.owner(), attributes.group(), attributes.permissions()));
    }

    /** Puts the owner, the group and the mode of a directory back as {@link #save} read them. */
    void restore(Saved saved) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(saved.path(), PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        PosixFileAttributes now = view.readAttributes();
        if (now.isSymbolicLink()) {
            throw new FileSystemException(saved.path().toString(), null, "symbolic link");
        }
        if (!Objects.equals(now.owner(), saved.owner())) {
            view.setOwner(saved.owner());
        }
        if (!Objects.equals(now.group(), saved.group())) {
            view.setGroup(saved.group());
        }
        FileModes.apply(saved.path(), saved.mode());
    }

    /** The three jars of the product to {@code lib} and the JDBC driver jar to {@code drivers}. */
    void copyProduct(Path jars, Path driver, Path lib, Path drivers) throws IOException {
        for (String jar : List.of(DeployLayout.CLI_JAR, DeployLayout.MCP_JAR, DeployLayout.ADMIN_JAR)) {
            files.copy(jars.resolve(jar), lib.resolve(jar));
        }
        files.copy(driver, drivers.resolve(driver.getFileName().toString()));
    }

    /**
     * The file system decides by the attribute views it supports, never by the name of the operating system.
     *
     * @throws FailureException HOST_UNSUPPORTED naming the view
     */
    void requireView(FileSystem fileSystem, String view) {
        if (!fileSystem.supportedFileAttributeViews().contains(view)) {
            throw new FailureException(ErrorCode.HOST_UNSUPPORTED, view);
        }
    }

    /**
     * The path (or the account) and the kind of the failure; never the text of the exception. A backup that holds other
     * bytes than its file names both files.
     */
    static FailureException failure(IOException e) {
        String subject = e instanceof DeployFiles.BackupDiffersException differs ? differs.files()
                : e instanceof FileSystemException failed && failed.getFile() != null ? failed.getFile()
                : e instanceof UserPrincipalNotFoundException missing ? missing.getName() : "-";
        return new FailureException(ErrorCode.HOST_FAILED, e, subject, e.getClass().getSimpleName());
    }
}
