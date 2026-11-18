package org.pgledger.cli.deploy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
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
 * The rights are the ones the installer gave with {@code icacls}: the home is closed to every account but SYSTEM and
 * Administrators; the one service account (LocalService) reads and runs the jars, changes the settings and writes its logs and
 * the attachments. The secrets are the installing user's (a folder made by {@code prepare-host} has the ACL of
 * {@link org.pgledger.files.ServicePrincipalAcl}: the installing user with everything, LocalService with
 * read), and nothing is handed over to the service account (the installing user stays the owner), and no privilege is used. An ACL is written whole ({@code setAcl}) and is not
 * propagated to the children by the file system: this class sets it on each path itself, and a folder's entries are inherited
 * by what is made in it afterwards.
 *
 * <p>A component, because {@link #set} changes the file system; the entries and the sets of rights are values and are built by
 * static functions ({@link #allow}, {@link #closed}, {@link #closedWith} ...) that touch nothing.
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

    static {
        Set<AclEntryPermission> modify = EnumSet.allOf(AclEntryPermission.class);
        modify.remove(AclEntryPermission.WRITE_ACL);
        modify.remove(AclEntryPermission.WRITE_OWNER);
        MODIFY = Collections.unmodifiableSet(modify);
    }

    /** One ALLOW entry; a directory's entry is inherited by the files and directories created in it. */
    static AclEntry allow(UserPrincipal who, Set<AclEntryPermission> permissions, boolean directory) {
        AclEntry.Builder entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(who).setPermissions(permissions);
        if (directory) {
            entry.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
        }
        return entry.build();
    }

    /** The home: SYSTEM and Administrators with everything, nobody else. */
    static List<AclEntry> closed(UserPrincipal system, UserPrincipal administrators) {
        return List.of(allow(system, FULL, true), allow(administrators, FULL, true));
    }

    /**
     * A folder of the installation: {@link #closed} and the service account with these rights, all inherited by what is made in the
     * folder (the jars and the settings that the installer writes later, and what the service writes).
     */
    static List<AclEntry> closedWith(UserPrincipal system, UserPrincipal administrators, UserPrincipal service,
            Set<AclEntryPermission> rights) {
        List<AclEntry> acl = new ArrayList<>(closed(system, administrators));
        acl.add(allow(service, rights, true));
        return List.copyOf(acl);
    }

    private static AclFileAttributeView view(Path path) {
        return Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    }

    /** Replaces the ACL of the path. The owner is never changed. */
    void set(Path path, List<AclEntry> acl) throws IOException {
        view(path).setAcl(acl);
    }

    /** What a path had before a run changed it: put back by {@link #restore} when the run fails. */
    record Saved(Path path, List<AclEntry> acl) {
    }

    /** The ACL of the path, read before the first change (reading needs only the right to read the ACL). */
    Saved save(Path path) throws IOException {
        return new Saved(path, view(path).getAcl());
    }

    /** Puts the path back as {@link #save} found it. A path whose ACL is as it was is not touched. */
    void restore(Saved saved) throws IOException {
        AclFileAttributeView view = view(saved.path());
        if (!Objects.equals(saved.acl(), view.getAcl())) {
            view.setAcl(saved.acl());
        }
    }
}
