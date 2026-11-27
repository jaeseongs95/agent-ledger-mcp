// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli.deploy;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.veridger.files.ServicePrincipalAcl;
import org.veridger.files.WellKnownAccount;
import org.veridger.cli.CliOptions;
import org.veridger.cli.ErrorCode;
import org.veridger.cli.UsageException;
import org.veridger.cli.deploy.DeployLayout.Platform;
import org.veridger.cli.secret.ProcessUser;
import org.veridger.cli.secret.SecretFolder;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * A personal PC or a Windows server: the services are WinSW configurations ({@link WinswServices}) below one home
 * directory ({@code --home}, for example {@code C:/ProgramData/<name>}), run by the one account LocalService.
 * {@code prepare-host} (run in an elevated prompt) closes the home and its folders to every account but SYSTEM and
 * Administrators and gives the service account in the same step what it needs ({@link WindowsAccess}): read and run the jars
 * (the folders {@code lib} and {@code etc\drivers}: nothing of the services writes there), change the settings folder (where the driver
 * page of the admin web writes its registration), write the logs, the data folder and the attachment folder. It copies the jars, the two WinSW executables and the driver; what it
 * makes inside these folders inherits those entries. {@code mkdir}, {@code icacls} and {@code copy} did this.
 *
 * <p>The secrets are the installing user's: the secret directory with its folders of the uses ({@link SecretFolder}) and the folder of
 * the attachment keys ({@code etc\attachment-keys}, beside the secrets, in no configuration tree) are made with the ACL
 * of {@link ServicePrincipalAcl} (the installing user with everything, the service account with read; the entry of the settings
 * folder for the service account to modify is not carried over, because the ACL is written at once), and what the store of
 * secrets and the key setup make in them has the same two entries. Why the service account reads: it runs the services, which
 * read the secrets, and the installation does not hand the secrets over to it (the installing user stays the owner and no
 * privilege to take ownership is used). An existing folder of the secrets is never changed (a re-run by the
 * same user finds everything as it left it; the personal edition has one user). A run that fails at any step puts the ACL of the
 * folders it changed back as it found them: the services of the installation, stopped for the run, can start again.
 */
@Component
final class WindowsPlatform implements ServicePlatform, ServiceWrappers {

    /** A drive and plain segments, with {@code /}: the form every path of a Windows installation is written in. */
    private static final String PATH = "[A-Za-z]:(?:/" + DeployForms.SEGMENT + ")+";
    /** The Java runtime of Windows lives in a directory with a space ({@code Program Files}); it is only ever an XML element. */
    private static final String JAVA = "[A-Za-z]:(?:/(?!\\.{1,2}(?:/|$))[A-Za-z0-9._+ -]+)+";

    private final HostAccounts accounts;
    private final ProcessUser processUser;
    private final WindowsAccess access;
    private final HostFiles host;
    private final DeployFiles files;

    WindowsPlatform(HostAccounts accounts, ProcessUser processUser, WindowsAccess access, HostFiles host,
            DeployFiles files) {
        this.accounts = accounts;
        this.processUser = processUser;
        this.access = access;
        this.host = host;
        this.files = files;
    }

    @Override
    public Platform platform() {
        return Platform.WINDOWS;
    }

    /**
     * A Windows path is written with a drive letter, {@code C:/x}; below a root the drive letter is a directory name, so it is
     * {@code <root>/C/x}.
     */
    @Override
    public Path hostPath(Path root, String path) {
        return root == null ? files.ownPath(path)
                : root.resolve(path.replaceFirst("^([A-Za-z]):", "$1").replaceFirst("^/", ""));
    }

    @Override
    public DeployLayout layout(String name, String home) {
        if (home == null) {
            throw new UsageException(ErrorCode.MISSING_ARGUMENT);
        }
        return DeployLayout.windows(name, DeployForms.requirePath(home, PATH, DeployOptions.HOME));
    }

    @Override
    public List<String> refusedOptions(DeployParameters p) {
        List<String> refused = new ArrayList<>();
        if (!DeployForms.isPath(p.java(), JAVA)) {
            refused.add(DeployOptions.JAVA);
        }
        return refused;
    }

    @Override
    public List<RenderedFile> serviceDefinitions(DeployParameters p) {
        DeployLayout layout = p.layout();
        Service mcp = Service.mcp(layout);
        Service admin = Service.admin(layout);
        return List.of(
                new RenderedFile(layout.lib() + "/" + mcp.name() + ".xml", WinswServices.configuration(p, mcp)),
                new RenderedFile(layout.lib() + "/" + admin.name() + ".xml", WinswServices.configuration(p, admin)));
    }

    @Override
    public List<Path> wrappers(ApplicationArguments arguments) {
        return List.of(Path.of(CliOptions.required(arguments, DeployOptions.WINSW)));
    }

    @Override
    public void prepare(DeployLayout layout, Path root, Path jars, Path driver, List<Path> wrappers) throws IOException {
        Path winsw = wrappers.getFirst();
        Path lib = hostPath(root, layout.lib());
        Path etc = hostPath(root, layout.etc());
        Path logs = hostPath(root, layout.logs());
        FileSystem fileSystem = lib.getFileSystem();
        host.requireView(fileSystem, "acl");
        UserPrincipal system = accounts.wellKnown(fileSystem, WellKnownAccount.SYSTEM);
        UserPrincipal administrators = accounts.wellKnown(fileSystem, WellKnownAccount.ADMINISTRATORS);
        UserPrincipal service = accounts.wellKnown(fileSystem, WellKnownAccount.SERVICE);
        UserPrincipal installer = processUser.current(fileSystem);
        Path home = lib.getParent();
        Path drivers = etc.resolve("drivers");
        Path driverState = hostPath(root, layout.driverStateDirectory());
        Path update = hostPath(root, layout.updateDirectory());
        Path data = hostPath(root, layout.data());
        Path attachments = hostPath(root, layout.attachmentDirectory());
        // the secret directory and one folder per use (SecretFolder), then the key folder of the attachments, which is beside them
        // and in no folder that a process imports as a configuration tree
        List<Path> secretFolders = new ArrayList<>(List.of(hostPath(root, layout.secretDirectory())));
        for (SecretFolder folder : SecretFolder.all()) {
            secretFolders.add(hostPath(root, layout.secretFolder(folder)));
        }
        secretFolders.add(hostPath(root, layout.attachmentKeyDirectory()));
        // The folders and what the service account may do in each; the secrets are not among them (see below). The settings
        // folder itself is read-only for the service account so it can never rewrite application.yaml or local.yaml; only the
        // dedicated driver-state and update subdirectories grant modify rights to the service account.
        List<Folder> folders = List.of(new Folder(lib, WindowsAccess.READ_EXECUTE),
                new Folder(etc, WindowsAccess.READ_EXECUTE), new Folder(drivers, WindowsAccess.READ_EXECUTE),
                new Folder(driverState, WindowsAccess.MODIFY), new Folder(update, WindowsAccess.MODIFY),
                new Folder(logs, WindowsAccess.MODIFY), new Folder(data, WindowsAccess.MODIFY),
                new Folder(attachments, WindowsAccess.MODIFY));
        // Verify every pre-existing installation path is neither a symlink nor owned by an untrusted account before an elevated
        // run modifies ACLs or writes beneath it (prevents C:\ProgramData pre-creation squat / junction takeover).
        Set<UserPrincipal> trustedOwners = Set.of(system, administrators, installer);
        List<Path> managedPaths = new ArrayList<>(List.of(home, lib, etc, drivers, driverState, update, logs, data,
                attachments));
        for (Path path : managedPaths) {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                access.requireTrustedOwner(path, trustedOwners);
            }
        }
        for (Path secretFolder : secretFolders) {
            if (Files.exists(secretFolder, LinkOption.NOFOLLOW_LINKS)) {
                access.requireTrustedOwner(secretFolder, trustedOwners);
                if (!Files.isDirectory(secretFolder, LinkOption.NOFOLLOW_LINKS)) {
                    throw new FileSystemException(secretFolder.toString(), null, "not a directory");
                }
            }
        }
        // What a run changes is read first, so a failed run can put it back (nothing is changed by reading).
        List<WindowsAccess.Saved> saved = new ArrayList<>();
        for (Path path : managedPaths) {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                saved.add(access.save(path));
            }
        }
        try {
            // the home first: a folder made below it takes what the home gives, and the file system propagates nothing by itself
            Files.createDirectories(home);
            access.set(home, WindowsAccess.closed(system, administrators));
            for (Folder folder : folders) {
                Files.createDirectories(folder.path());
                access.set(folder.path(), WindowsAccess.closedWith(system, administrators, service, folder.serviceRights()));
            }
            // The folders of the secrets and the folder of the attachment keys: the installing user with everything and the
            // service account with read, set when the folder is made. An existing one is never changed. The settings folder holds an
            // entry of the service account to modify that a new folder inherits, so the ACL is written at once, not left to inheritance.
            List<AclEntry> forSecrets = ServicePrincipalAcl.ownerAndService(installer, service, true);
            for (Path secretFolder : secretFolders) {
                if (!Files.isDirectory(secretFolder, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(secretFolder);
                    access.set(secretFolder, forSecrets);
                }
            }
            host.copyProduct(jars, driver, lib, drivers);
            files.copy(winsw, lib.resolve(layout.mcpName() + ".exe"));
            files.copy(winsw, lib.resolve(layout.adminName() + ".exe"));
        } catch (IOException failure) {
            putBack(saved, failure);
            throw failure;
        }
    }

    /** A folder of the installation and what the service account may do in it. */
    private record Folder(Path path, Set<AclEntryPermission> serviceRights) {
    }

    /**
     * Puts back what was saved, the last one first (a file before its folder), and goes on when one cannot be put back: the
     * failure of the run stays the one that is reported, and what could not be put back is attached to it.
     */
    private void putBack(List<WindowsAccess.Saved> saved, IOException failure) {
        for (WindowsAccess.Saved before : saved.reversed()) {
            try {
                access.restore(before);
            } catch (IOException e) {
                failure.addSuppressed(e);
            }
        }
    }
}
