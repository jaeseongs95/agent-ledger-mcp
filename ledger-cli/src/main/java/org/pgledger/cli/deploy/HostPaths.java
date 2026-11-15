package org.pgledger.cli.deploy;

import java.nio.file.Path;

/**
 * Where a path, as a platform writes it into the files it renders ({@code /etc/x} for systemd and nginx, {@code C:/ProgramData/x}
 * for WinSW), is on the host that runs the command. The syntax of those paths is the platform's own and belongs to its
 * {@link ServicePlatform}; the code that writes, copies or looks at the files of an installation asks it and holds no form of
 * any operating system.
 */
public interface HostPaths {

    /**
     * @param root the directory every path is put below (a test, or a staging directory), or null for the real path
     * @param path a path in the syntax of this platform
     * @return the path below {@code root}, or the path itself when there is no root
     * @throws org.pgledger.cli.UsageException INVALID_DEPLOY_VALUE naming {@code platform} when there is no root and the path
     *         is not an absolute path of this host (it is a path of the other platform)
     */
    Path hostPath(Path root, String path);
}
