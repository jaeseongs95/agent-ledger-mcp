// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli.deploy;

import java.util.List;
import org.veridger.cli.secret.SecretFolder;

/**
 * One of the two services of an instance, as every platform runs it: a name, a jar, the settings file of its own and the folders
 * of the secrets it reads. The arguments of the Java command line are the same on every platform; only the wrapper around them (a
 * systemd unit, a WinSW configuration) differs.
 *
 * @param name          the name of the service (unit name, service id)
 * @param description   what it is
 * @param jar           the jar below {@code lib}
 * @param settingsFile  the settings file of this service below {@code etc} (it holds the port and the allowed hosts)
 * @param secretFolders the folders of the secrets below the secret directory that the service imports, and no others (least
 *                      privilege: the two services run as one operating system user, so the files could be read by either, but
 *                      what a process does not import is not in its {@code Environment}); see {@link SecretFolder}
 */
record Service(String name, String description, String jar, String settingsFile, List<SecretFolder> secretFolders) {

    static Service mcp(DeployLayout layout) {
        return new Service(layout.mcpName(), "the ledger MCP server", DeployLayout.MCP_JAR, "mcp.yaml",
                List.of(SecretFolder.COMMON, SecretFolder.MCP));
    }

    static Service admin(DeployLayout layout) {
        return new Service(layout.adminName(), "the ledger admin web", DeployLayout.ADMIN_JAR, "admin.yaml",
                List.of(SecretFolder.COMMON, SecretFolder.ADMIN));
    }

    /** {@code <the service JVM preset> -jar <jar> --spring.config.import=<the sources of this service>} ({@link JvmPreset}). */
    String arguments(DeployLayout layout) {
        return JvmPreset.serviceJar(layout.lib() + "/" + jar) + " --spring.config.import="
                + DeployRenderer.configImport(layout, this);
    }
}
