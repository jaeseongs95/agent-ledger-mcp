package org.pgledger.cli.deploy;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.pgledger.cli.CliOptions;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.UsageException;
import org.pgledger.cli.deploy.DeployLayout.Platform;
import org.pgledger.cli.secret.ProcessUser;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * A personal PC or a Windows server: the services are WinSW configurations ({@link WinswServices}) below one home
 * directory ({@code --home}, for example {@code C:/ProgramData/<name>}), run by the one account LocalService.
 * {@code prepare-host} (run in an elevated prompt) first gives the secrets of an earlier run back to the installer (the folder
 * first, then its files) so the commands of the run can read them, then closes the home and its folders to every account but
 * SYSTEM and Administrators ({@link WindowsAccess}), and copies the jars, the two WinSW executables and the driver. Taking the
 * secrets back is the step that the file system can refuse (the service account owns them), so it comes before anything is
 * closed, and a run that fails at any step puts the owner and the ACL of everything it changed back as it found them: the
 * services of the installation, stopped for the run, can start again;
 * {@code seal-host} then gives the service account what it needs (read and run the jars, change the settings folder, where the
 * driver page of the admin web writes its registration, write the logs) and hands the secrets over, the files first and then
 * the folder: the service account is their owner and has full access, Administrators can only see that a file exists, and
 * nobody else, the installer included, has any. {@code mkdir}, {@code icacls} and {@code copy} did this.
 *
 * <p>The secrets of an earlier run belong to the service account, and Administrators may only see that they exist: an
 * administrator can take them back, and hand them over again, only with the privileges to take ownership that its elevated
 * token holds disabled ({@link WindowsAccess#reassign}, {@link WindowsPrivileges}); taking ownership comes first, the ACL is
 * written by the owner.
 */
@Component
final class WindowsPlatform implements ServicePlatform, ServiceWrappers, HostSealing {

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
        UserPrincipal system = accounts.user(fileSystem, WindowsAccess.SYSTEM);
        UserPrincipal administrators = accounts.user(fileSystem, WindowsAccess.ADMINISTRATORS);
        UserPrincipal installer = processUser.current(fileSystem);
        List<AclEntry> closed = WindowsAccess.closed(system, administrators);
        Path home = lib.getParent();
        Path drivers = etc.resolve("drivers");
        Path secrets = etc.resolve("secrets");
        Path data = hostPath(root, layout.data());
        Path attachments = hostPath(root, layout.attachmentDirectory());
        Path keys = hostPath(root, layout.attachmentKeyDirectory());
        // What a run changes is read first, so a failed run can put it back (nothing is changed by reading). The folder of the keys
        // is inside the secrets: it is read, like the files there, once the secrets are the installer's.
        List<WindowsAccess.Saved> saved = new ArrayList<>();
        for (Path path : List.of(home, lib, etc, drivers, secrets, logs, data, attachments)) {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                saved.add(access.save(path));
            }
        }
        try {
            // After an earlier run the secrets belong to the service account, and Administrators may only see that a file
            // exists. They are given back to the installer for this run first, before anything is closed: that is the step
            // that can be refused, and a refusal then leaves the home as it was. The folder first (a file is listed and
            // opened through it), then its files; seal-host hands them over again at the end.
            if (Files.isDirectory(secrets, LinkOption.NOFOLLOW_LINKS)) {
                access.reassign(secrets, installer, installer, WindowsAccess.takenBack(installer, true));
                SecretTree tree = secretTree(secrets);
                for (Path folder : tree.folders()) {
                    saved.add(access.save(folder));
                    access.reassign(folder, installer, installer, WindowsAccess.takenBack(installer, true));
                }
                for (Path file : tree.files()) {
                    saved.add(access.save(file));
                    access.reassign(file, installer, installer, WindowsAccess.takenBack(installer, false));
                }
            }
            // the home first: a folder made below it takes what the home gives, and the file system propagates nothing by itself
            Files.createDirectories(home);
            access.set(home, null, closed);
            for (Path folder : List.of(lib, etc, drivers, logs, data, attachments)) {
                Files.createDirectories(folder);
                access.set(folder, null, closed);
            }
            if (!Files.isDirectory(secrets, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(secrets);
                access.reassign(secrets, installer, installer, WindowsAccess.takenBack(installer, true));
            }
            // the folder of the keys of the attachments: the installer's alone, like the secrets, until seal-host hands it over
            if (!Files.isDirectory(keys, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(keys);
                access.reassign(keys, installer, installer, WindowsAccess.takenBack(installer, true));
            }
            host.copyProduct(jars, driver, lib, drivers);
            files.copy(winsw, lib.resolve(layout.mcpName() + ".exe"));
            files.copy(winsw, lib.resolve(layout.adminName() + ".exe"));
        } catch (IOException failure) {
            putBack(saved, failure);
            throw failure;
        }
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

    /**
     * What is below the secrets folder: the folders (the folder of the keys of the attachments), the shallower first, and the
     * regular files at every depth, by name. A link is neither.
     */
    private record SecretTree(List<Path> folders, List<Path> files) {
    }

    private static SecretTree secretTree(Path secrets) throws IOException {
        List<Path> folders = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(secrets, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (!Objects.equals(directory, secrets)) {
                    folders.add(directory);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isRegularFile()) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        folders.sort(Comparator.comparingInt(Path::getNameCount).thenComparing(Comparator.naturalOrder()));
        files.sort(Comparator.naturalOrder());
        return new SecretTree(List.copyOf(folders), List.copyOf(files));
    }

    @Override
    public void seal(DeployLayout layout, Path root) throws IOException {
        Path lib = hostPath(root, layout.lib());
        Path etc = hostPath(root, layout.etc());
        Path logs = hostPath(root, layout.logs());
        Path secrets = etc.resolve("secrets");
        Path data = hostPath(root, layout.data());
        Path attachments = hostPath(root, layout.attachmentDirectory());
        FileSystem fileSystem = lib.getFileSystem();
        host.requireView(fileSystem, "acl");
        UserPrincipal service = accounts.user(fileSystem, DeployLayout.WINDOWS_SERVICE_ACCOUNT);
        UserPrincipal administrators = accounts.user(fileSystem, WindowsAccess.ADMINISTRATORS);
        UserPrincipal installer = processUser.current(fileSystem);
        access.grantTree(lib, service, WindowsAccess.READ_EXECUTE, secrets);
        access.grantTree(etc, service, WindowsAccess.MODIFY, secrets);
        access.grantTree(logs, service, WindowsAccess.MODIFY, secrets);
        // The data folder and the attachment folder get the entry of the service account on the folder itself and not on what is in
        // it: what the service made there is its own, with an ACL for it alone, and the installer cannot change that ACL (a re-run
        // must not fail on it); a file or a folder it makes later is private to it from its first moment (PrivateFiles).
        access.grant(data, service, WindowsAccess.MODIFY);
        access.grant(attachments, service, WindowsAccess.MODIFY);
        // The folder is the installer's first (a run that did not take the secrets back, or one that failed half way, finds
        // it the service account's, and Administrators could not even list it); the files are handed over, then the folder.
        // The folder of the keys of the attachments is below it: its folders are taken first, its files are handed over, and its
        // folders are handed over before the secrets folder itself.
        access.reassign(secrets, installer, installer, WindowsAccess.takenBack(installer, true));
        SecretTree tree = secretTree(secrets);
        for (Path folder : tree.folders()) {
            access.reassign(folder, installer, installer, WindowsAccess.takenBack(installer, true));
        }
        for (Path file : tree.files()) {
            access.reassign(file, installer, service, WindowsAccess.handedOver(service, administrators, false));
        }
        for (Path folder : tree.folders().reversed()) {
            access.reassign(folder, installer, service, WindowsAccess.handedOver(service, administrators, true));
        }
        access.reassign(secrets, installer, service, WindowsAccess.handedOver(service, administrators, true));
    }
}
