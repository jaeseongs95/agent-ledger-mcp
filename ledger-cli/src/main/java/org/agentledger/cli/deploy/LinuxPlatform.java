// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.cli.deploy;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;
import org.agentledger.cli.ErrorCode;
import org.agentledger.cli.UsageException;
import org.agentledger.cli.deploy.DeployLayout.Platform;
import org.agentledger.cli.secret.SecretFolder;
import org.springframework.stereotype.Component;

/**
 * A Linux server: the services are systemd units ({@link SystemdUnits}), the jars are below {@code /opt/<name>} and the
 * settings below {@code /etc/<name>}, owned by the system user of the instance. Run as root, {@code prepare-host} makes the
 * directories {@code <lib>} (root, 0755), {@code <etc>} (the service user, 0750), {@code <etc>/drivers} (root, 0755) and
 * {@code <etc>/secrets} with one folder per use ({@link SecretFolder}: {@code common}, {@code admin}, {@code mcp}, {@code tool}; the
 * service user, 0700), the folders of the attachments ({@code <etc>/attachment-keys}, beside the secrets and not inside a
 * configuration tree; the data folder {@code /var/lib/<name>} and {@code <data>/attachments}: the service user, 0700) and copies the three jars and the JDBC
 * driver jar with mode 0644 ({@code install -d} and {@code install -m} did this). The secrets are the service user's from the
 * start, and systemd needs no service wrapper (no {@link ServiceWrappers}). The paths of its files, and of the Java runtime that
 * the operator names, are the plain paths of a Linux file system.
 */
@Component
final class LinuxPlatform implements ServicePlatform {

    /** Plain path segments only (letters, digits and {@code . _ + -}), each with its slash: the form of every path of a Linux installation. */
    private static final String PATH = "(?:/" + DeployForms.SEGMENT + ")+";

    private final HostAccounts accounts;
    private final HostFiles host;
    private final DeployFiles files;

    LinuxPlatform(HostAccounts accounts, HostFiles host, DeployFiles files) {
        this.accounts = accounts;
        this.host = host;
        this.files = files;
    }

    @Override
    public Platform platform() {
        return Platform.LINUX;
    }

    /** A Linux path starts with {@code /}; below a root the slash is dropped, so {@code /etc/x} is {@code <root>/etc/x}. */
    @Override
    public Path hostPath(Path root, String path) {
        return root == null ? files.ownPath(path) : root.resolve(path.replaceFirst("^/", ""));
    }

    @Override
    public DeployLayout layout(String name, String home) {
        if (home != null) {
            // the home is the one directory of a Windows installation; a Linux one follows the name
            throw new UsageException(ErrorCode.INVALID_DEPLOY_VALUE, DeployOptions.HOME);
        }
        return DeployLayout.linux(name);
    }

    @Override
    public List<String> refusedOptions(DeployParameters p) {
        List<String> refused = new ArrayList<>();
        if (!DeployForms.isPath(p.java(), PATH)) {
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
                new RenderedFile(DeployLayout.UNIT_DIRECTORY + "/" + mcp.name() + ".service",
                        SystemdUnits.unit(p, mcp, "network-online.target", layout.attachmentDirectory())),
                new RenderedFile(DeployLayout.UNIT_DIRECTORY + "/" + admin.name() + ".service",
                        SystemdUnits.unit(p, admin, "network-online.target " + mcp.name() + ".service", layout.etc())));
    }

    @Override
    public void prepare(DeployLayout layout, Path root, Path jars, Path driver, List<Path> wrappers) throws IOException {
        Path lib = hostPath(root, layout.lib());
        Path etc = hostPath(root, layout.etc());
        FileSystem fileSystem = lib.getFileSystem();
        host.requireView(fileSystem, "posix");
        // the accounts are looked up before the first change: a host without the service user is refused with nothing done
        UserPrincipal user = accounts.user(fileSystem, layout.serviceUser());
        GroupPrincipal group = accounts.group(fileSystem, layout.serviceUser());
        Path drivers = etc.resolve("drivers");
        // the secrets: the directory and one folder per use (SecretFolder), each the service user's alone; the key folder of the
        // attachments is beside them, in no folder that a process imports as a configuration tree
        List<Path> secretFolders = new ArrayList<>(List.of(hostPath(root, layout.secretDirectory())));
        for (SecretFolder folder : SecretFolder.all()) {
            secretFolders.add(hostPath(root, layout.secretFolder(folder)));
        }
        // the attachments: the key folder, the data folder and the attachment folder below it, all the service user's
        // alone (a folder that exists gets the same owner and mode as a new one); the data of the installation is new with
        // this version, so nothing of it exists before
        secretFolders.add(hostPath(root, layout.attachmentKeyDirectory()));
        secretFolders.add(hostPath(root, layout.data()));
        secretFolders.add(hostPath(root, layout.attachmentDirectory()));
        // What a run changes is read first, so that a failed run can put it back, as the Windows platform does with the ACLs
        // (nothing is changed by reading): the services stopped for the run can then start again.
        List<HostFiles.Saved> saved = new ArrayList<>();
        List<Path> changed = new ArrayList<>(List.of(lib, etc, drivers));
        changed.addAll(secretFolders);
        for (Path path : changed) {
            host.save(path).ifPresent(saved::add);
        }
        try {
            host.directory(lib, "rwxr-xr-x", null, null);
            host.directory(etc, "rwxr-x---", user, group);
            host.directory(drivers, "rwxr-xr-x", null, null);
            for (Path secretFolder : secretFolders) {
                host.directory(secretFolder, "rwx------", user, group);
            }
            host.copyProduct(jars, driver, lib, drivers);
        } catch (IOException failure) {
            putBack(saved, failure);
            throw failure;
        }
    }

    /**
     * Puts back what was saved, the last one first, and goes on when one cannot be put back: the failure of the run stays the one
     * that is reported, and what could not be put back is attached to it.
     */
    private void putBack(List<HostFiles.Saved> saved, IOException failure) {
        for (HostFiles.Saved before : saved.reversed()) {
            try {
                host.restore(before);
            } catch (IOException e) {
                failure.addSuppressed(e);
            }
        }
    }
}
