package org.pgledger.cli.deploy;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
 * {@code prepare-host} (run in an elevated prompt) closes the home and its folders to every account but SYSTEM and
 * Administrators ({@link WindowsAccess}), gives the secrets of an earlier run back to the installer (the folder first, then
 * its files) so the commands of the run can read them, and copies the jars, the two WinSW executables and the driver;
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
final class WindowsPlatform implements ServicePlatform {

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

    @Override
    public DeployLayout layout(String name, String home) {
        if (home == null) {
            throw new UsageException(ErrorCode.MISSING_ARGUMENT);
        }
        return DeployLayout.windows(name, DeployForms.requirePath(home, DeployForms.WINDOWS_PATH, DeployOptions.HOME));
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
        Path lib = files.resolve(root, layout.lib());
        Path etc = files.resolve(root, layout.etc());
        Path logs = files.resolve(root, layout.logs());
        FileSystem fileSystem = lib.getFileSystem();
        host.requireView(fileSystem, "acl");
        UserPrincipal system = accounts.user(fileSystem, WindowsAccess.SYSTEM);
        UserPrincipal administrators = accounts.user(fileSystem, WindowsAccess.ADMINISTRATORS);
        UserPrincipal installer = processUser.current(fileSystem);
        List<AclEntry> closed = WindowsAccess.closed(system, administrators);
        // the home first: a folder made below it takes what the home gives, and the file system propagates nothing by itself
        Path home = lib.getParent();
        Files.createDirectories(home);
        access.set(home, null, closed);
        Path secrets = etc.resolve("secrets");
        for (Path folder : List.of(lib, etc, etc.resolve("drivers"), logs)) {
            Files.createDirectories(folder);
            access.set(folder, null, closed);
        }
        // After an earlier run the secrets belong to the service account, and the ACL of the folder gives Administrators nothing
        // but the right to see it: closing it like the others is refused. The installer takes it over (the folder first, then its
        // files) and gives it the ACL of this run; seal-host hands the secrets over again at the end.
        Files.createDirectories(secrets);
        access.reassign(secrets, installer, installer, WindowsAccess.takenBack(installer, true));
        for (Path file : secretFiles(secrets)) {
            access.reassign(file, installer, installer, WindowsAccess.takenBack(installer, false));
        }
        host.copyProduct(jars, driver, lib, etc.resolve("drivers"));
        files.copy(winsw, lib.resolve(layout.mcpName() + ".exe"));
        files.copy(winsw, lib.resolve(layout.adminName() + ".exe"));
    }

    /** The files in the secrets folder, by name. */
    private static List<Path> secretFiles(Path secrets) throws IOException {
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(secrets, Files::isRegularFile)) {
            stream.forEach(found::add);
        } catch (DirectoryIteratorException e) {
            throw e.getCause();
        }
        found.sort(Comparator.naturalOrder());
        return found;
    }

    @Override
    public void seal(DeployLayout layout, Path root) throws IOException {
        Path lib = files.resolve(root, layout.lib());
        Path etc = files.resolve(root, layout.etc());
        Path logs = files.resolve(root, layout.logs());
        Path secrets = etc.resolve("secrets");
        FileSystem fileSystem = lib.getFileSystem();
        host.requireView(fileSystem, "acl");
        UserPrincipal service = accounts.user(fileSystem, DeployLayout.WINDOWS_SERVICE_ACCOUNT);
        UserPrincipal administrators = accounts.user(fileSystem, WindowsAccess.ADMINISTRATORS);
        UserPrincipal installer = processUser.current(fileSystem);
        access.grantTree(lib, service, WindowsAccess.READ_EXECUTE, secrets);
        access.grantTree(etc, service, WindowsAccess.MODIFY, secrets);
        access.grantTree(logs, service, WindowsAccess.MODIFY, secrets);
        // The folder is the installer's first (a run that did not take the secrets back, or one that failed half way, finds
        // it the service account's, and Administrators could not even list it); the files are handed over, then the folder.
        access.reassign(secrets, installer, installer, WindowsAccess.takenBack(installer, true));
        for (Path file : secretFiles(secrets)) {
            access.reassign(file, installer, service, WindowsAccess.handedOver(service, administrators, false));
        }
        access.reassign(secrets, installer, service, WindowsAccess.handedOver(service, administrators, true));
    }
}
