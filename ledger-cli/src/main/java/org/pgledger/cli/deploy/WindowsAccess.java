package org.pgledger.cli.deploy;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The access of an installation on a file system with ACLs (Windows), written with {@link AclFileAttributeView} and no
 * {@code icacls}: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/attribute/AclFileAttributeView.html .
 * The rights are the ones the installer of 1.x/2.0 gave with {@code icacls}: the home is closed to every account but SYSTEM
 * and Administrators; the one service account reads and runs the jars, changes the settings and writes its logs; the secrets
 * belong to the service account alone, and Administrators keep only the right to see that a file exists. An ACL is written
 * whole ({@code setAcl}) and is not propagated to the children by the file system: this class sets it on each path itself.
 *
 * <p>A component, because {@link #set}, {@link #reassign} and {@link #grantTree} change the file system; the entries and the sets of rights
 * are values and are built by static functions ({@link #allow}, {@link #closed}, {@link #with} ...) that touch nothing.
 */
@Component
final class WindowsAccess {

    static final String SYSTEM = "NT AUTHORITY\\SYSTEM";
    static final String ADMINISTRATORS = "BUILTIN\\Administrators";

    static final Set<AclEntryPermission> FULL = Collections.unmodifiableSet(EnumSet.allOf(AclEntryPermission.class));
    /** icacls {@code RX}. */
    static final Set<AclEntryPermission> READ_EXECUTE = Collections.unmodifiableSet(EnumSet.of(AclEntryPermission.READ_DATA,
            AclEntryPermission.READ_NAMED_ATTRS, AclEntryPermission.EXECUTE, AclEntryPermission.READ_ATTRIBUTES,
            AclEntryPermission.READ_ACL, AclEntryPermission.SYNCHRONIZE));
    /** icacls {@code M}: everything but changing the ACL and the owner. */
    static final Set<AclEntryPermission> MODIFY;
    /** icacls {@code RA,RC,S}: to see that a file exists, nothing more. */
    static final Set<AclEntryPermission> EXISTS_ONLY = Collections.unmodifiableSet(EnumSet.of(
            AclEntryPermission.READ_ATTRIBUTES, AclEntryPermission.READ_ACL, AclEntryPermission.SYNCHRONIZE));

    static {
        Set<AclEntryPermission> modify = EnumSet.allOf(AclEntryPermission.class);
        modify.remove(AclEntryPermission.WRITE_ACL);
        modify.remove(AclEntryPermission.WRITE_OWNER);
        MODIFY = Collections.unmodifiableSet(modify);
    }

    /** Enables the privileges that taking a file over needs ({@link #restore}, {@link #reassign}). */
    private final TokenPrivileges privileges;

    WindowsAccess(TokenPrivileges privileges) {
        this.privileges = privileges;
    }

    /** One ALLOW entry; a directory's entry is inherited by the files and directories created in it. */
    static AclEntry allow(UserPrincipal who, Set<AclEntryPermission> permissions, boolean directory) {
        AclEntry.Builder entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(who).setPermissions(permissions);
        if (directory) {
            entry.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
        }
        return entry.build();
    }

    /** The home and its folders: SYSTEM and Administrators with everything, nobody else. */
    static List<AclEntry> closed(UserPrincipal system, UserPrincipal administrators) {
        return List.of(allow(system, FULL, true), allow(administrators, FULL, true));
    }

    /** The secrets given back to the installer for the run: the installer alone. */
    static List<AclEntry> takenBack(UserPrincipal installer, boolean directory) {
        return List.of(allow(installer, FULL, directory));
    }

    /** The secrets handed to the service account: it alone has access; Administrators can see that a file exists. */
    static List<AclEntry> handedOver(UserPrincipal service, UserPrincipal administrators, boolean directory) {
        return List.of(allow(service, FULL, directory), allow(administrators, EXISTS_ONLY, directory));
    }

    /** The ACL with the entry of that principal (an ALLOW entry it had before is replaced); the others are kept. */
    static List<AclEntry> with(List<AclEntry> current, AclEntry added) {
        List<AclEntry> acl = new ArrayList<>();
        for (AclEntry entry : current) {
            if (!(entry.type() == AclEntryType.ALLOW && Objects.equals(entry.principal(), added.principal()))) {
                acl.add(entry);
            }
        }
        acl.add(added);
        return acl;
    }

    private static AclFileAttributeView view(Path path) {
        return Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    }

    /** Gives the path to {@code owner} (when not null) and replaces its ACL. */
    void set(Path path, UserPrincipal owner, List<AclEntry> acl) throws IOException {
        AclFileAttributeView view = view(path);
        if (owner != null) {
            view.setOwner(owner);
        }
        view.setAcl(acl);
    }

    /** What a path had before a run changed it: put back by {@link #restore} when the run fails. */
    record Saved(Path path, UserPrincipal owner, List<AclEntry> acl) {
    }

    /** The owner and the ACL of the path, read before the first change (reading needs only the right to read the ACL). */
    Saved save(Path path) throws IOException {
        AclFileAttributeView view = view(path);
        return new Saved(path, view.getOwner(), view.getAcl());
    }

    /**
     * Puts the path back as {@link #save} found it, ACL first and owner last (the owner may not write the ACL of a path it
     * gave away). A path that is as it was is not touched, so a path whose change never began, and which the caller could not
     * change anyway, costs nothing.
     */
    void restore(Saved saved) throws IOException {
        AclFileAttributeView view = view(saved.path());
        boolean sameAcl = Objects.equals(saved.acl(), view.getAcl());
        boolean sameOwner = Objects.equals(saved.owner(), view.getOwner());
        if (sameAcl && sameOwner) {
            return;
        }
        try (TokenPrivileges.Enabled enabled = privileges.enable(TokenPrivileges.TAKE_OWNERSHIP, TokenPrivileges.RESTORE)) {
            try {
                if (!sameAcl) {
                    view.setAcl(saved.acl());
                }
                if (!sameOwner) {
                    view.setOwner(saved.owner());
                }
            } catch (AccessDeniedException e) {
                throw refused(saved.path(), enabled, e);
            }
        }
    }

    /**
     * Makes the installer the owner of the path whoever owns it now, writes its ACL as that owner, and then gives it to
     * {@code owner} (the installer itself for the secrets of a run, the service account for the secrets that are handed over).
     * The ACL is written before the last owner change because the owner of a path may write its ACL, while the ACL that is
     * written leaves the installer no right to change the owner: that last step, and the first one for a path that the
     * service account owns and whose ACL gives Administrators nothing but the right to see it, are possible only with
     * {@code SeTakeOwnershipPrivilege} (to take it) and {@code SeRestorePrivilege} (to name another owner), which an elevated
     * token holds disabled and {@link TokenPrivileges} enables for this one operation. {@code setAcl} of the JDK enables none,
     * and its {@code setOwner} only the second.
     *
     * @throws PrivilegeNotHeldException when the process does not hold the privileges (it is not elevated) and the file system
     *         refused the operation
     */
    void reassign(Path path, UserPrincipal installer, UserPrincipal owner, List<AclEntry> acl) throws IOException {
        AclFileAttributeView view = view(path);
        try (TokenPrivileges.Enabled enabled = privileges.enable(TokenPrivileges.TAKE_OWNERSHIP, TokenPrivileges.RESTORE)) {
            try {
                if (!Objects.equals(installer, view.getOwner())) {
                    view.setOwner(installer);
                }
                view.setAcl(acl);
                if (!Objects.equals(owner, installer)) {
                    view.setOwner(owner);
                }
            } catch (AccessDeniedException e) {
                throw refused(path, enabled, e);
            }
        }
    }

    /** A refusal of the file system is the missing privilege when the token does not hold them, and it is the refusal otherwise. */
    private static IOException refused(Path path, TokenPrivileges.Enabled enabled, AccessDeniedException refusal) {
        return enabled.allHeld() ? refusal : new PrivilegeNotHeldException(path, refusal);
    }

    /**
     * Adds the entry of {@code who} to the ACL of this folder only (a folder it makes and a file it makes in the folder inherit
     * it), and leaves what is already in it alone.
     */
    void grant(Path folder, UserPrincipal who, Set<AclEntryPermission> permissions) throws IOException {
        AclFileAttributeView view = view(folder);
        view.setAcl(with(view.getAcl(), allow(who, permissions, true)));
    }

    /** Adds the entry of {@code who} to the ACL of the folder and of everything in it, but for the subtree {@code skipped}. */
    void grantTree(Path folder, UserPrincipal who, Set<AclEntryPermission> permissions, Path skipped)
            throws IOException {
        Files.walkFileTree(folder, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (Objects.equals(directory, skipped)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                add(directory, true);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                add(file, false);
                return FileVisitResult.CONTINUE;
            }

            private void add(Path path, boolean directory) throws IOException {
                AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS);
                view.setAcl(with(view.getAcl(), allow(who, permissions, directory)));
            }
        });
    }
}
