package org.pgledger.cli.deploy;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.UsageException;
import org.pgledger.cli.deploy.DeployLayout.Platform;
import org.springframework.stereotype.Component;

/**
 * A Linux server: the services are systemd units ({@link SystemdUnits}), the jars are below {@code /opt/<name>} and the
 * settings below {@code /etc/<name>}, owned by the system user of the instance. Run as root, {@code prepare-host} makes the
 * directories {@code <lib>} (root, 0755), {@code <etc>} (the service user, 0750), {@code <etc>/drivers} (root, 0755) and
 * {@code <etc>/secrets} (the service user, 0700) and copies the three jars and the JDBC driver jar with mode 0644 ({@code
 * install -d} and {@code install -m} did this). The secrets are the service user's from the start, so there is nothing to seal
 * (no {@link HostSealing}) and systemd needs no service wrapper (no {@link ServiceWrappers}); the nginx site and the automatic
 * certificate are Linux's ({@link TlsHosting}). The paths of its files, and of the Java runtime and the nginx site that the
 * operator names, are the plain paths of a Linux file system.
 */
@Component
final class LinuxPlatform implements ServicePlatform, TlsHosting {

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
        DeployParameters.Nginx site = p.nginx();
        if (site != null) {
            if (!DeployForms.isPath(site.conf(), PATH)) {
                refused.add(DeployOptions.NGINX_CONF);
            }
            if (!DeployForms.isPath(site.certificate(), PATH)) {
                refused.add(DeployOptions.TLS_CERTIFICATE);
            }
            if (!DeployForms.isPath(site.key(), PATH)) {
                refused.add(DeployOptions.TLS_KEY);
            }
            // the site serves the public host names of the MCP server: without one there is nothing to serve (the admin web has a
            // block only when it has a public name; without one it is reached on loopback)
            if (p.mcpHosts().isEmpty()) {
                refused.add(DeployOptions.MCP_HOST);
            }
        }
        return refused;
    }

    @Override
    public List<RenderedFile> serviceDefinitions(DeployParameters p) {
        DeployLayout layout = p.layout();
        Service mcp = Service.mcp(layout);
        Service admin = Service.admin(layout);
        List<RenderedFile> files = new ArrayList<>(List.of(
                new RenderedFile(DeployLayout.UNIT_DIRECTORY + "/" + mcp.name() + ".service",
                        SystemdUnits.unit(p, mcp, "network-online.target", null)),
                new RenderedFile(DeployLayout.UNIT_DIRECTORY + "/" + admin.name() + ".service",
                        SystemdUnits.unit(p, admin, "network-online.target " + mcp.name() + ".service", layout.etc()))));
        if (p.nginx() != null && p.nginx().acme() != null) {
            // the renewal of the automatic certificate: a oneshot service and the timer that starts it
            files.add(new RenderedFile(DeployLayout.UNIT_DIRECTORY + "/" + layout.tlsRenewName() + ".service",
                    SystemdUnits.renewService(p)));
            files.add(new RenderedFile(DeployLayout.UNIT_DIRECTORY + "/" + layout.tlsRenewName() + ".timer",
                    SystemdUnits.renewTimer(p)));
        }
        return List.copyOf(files);
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
        host.directory(lib, "rwxr-xr-x", null, null);
        host.directory(etc, "rwxr-x---", user, group);
        host.directory(etc.resolve("drivers"), "rwxr-xr-x", null, null);
        host.directory(etc.resolve("secrets"), "rwx------", user, group);
        host.copyProduct(jars, driver, lib, etc.resolve("drivers"));
    }

    @Override
    public void prepareTls(DeployLayout layout, Path root) throws IOException {
        Path tls = hostPath(root, layout.tlsDirectory());
        Path webroot = hostPath(root, layout.acmeWebroot());
        FileSystem fileSystem = tls.getFileSystem();
        host.requireView(fileSystem, "posix");
        UserPrincipal user = accounts.user(fileSystem, layout.serviceUser());
        GroupPrincipal group = accounts.group(fileSystem, layout.serviceUser());
        // the keys are the service user's alone; the web root is read by the web server, which runs as another account
        host.directory(tls, "rwx------", user, group);
        host.directory(webroot, "rwxr-xr-x", user, group);
    }
}
