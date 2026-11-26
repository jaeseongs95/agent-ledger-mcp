// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli.deploy;

import java.util.ArrayList;
import java.util.List;
import org.veridger.cli.secret.SecretFolder;
import org.springframework.stereotype.Component;

/**
 * Renders the files of the deployment from validated parameters: the definition of the two services that the platform runs
 * them with ({@link ServicePlatform#serviceDefinitions}: {@link SystemdUnits} on Linux, {@link WinswServices} on Windows),
 * and the settings files ({@link ServiceSettings}, every platform). It is a pure function of its input (no clock, no host name, no environment, no file access), so the
 * output is the same text every time and a test needs neither root, nor Linux, nor Windows. A target that needs another
 * definition is one more {@link ServicePlatform}; this class does not know the platforms.
 */
@Component
public final class DeployRenderer {

    private final ServicePlatforms platforms;

    DeployRenderer(ServicePlatforms platforms) {
        this.platforms = platforms;
    }

    /** The files in a fixed order: the service definitions, then the settings files. */
    public List<RenderedFile> render(DeployParameters p) {
        DeployLayout layout = p.layout();
        Service mcp = Service.mcp(layout);
        Service admin = Service.admin(layout);
        List<RenderedFile> files = new ArrayList<>(platforms.of(layout.platform()).serviceDefinitions(p));
        files.add(new RenderedFile(layout.etc() + "/application.yaml", ServiceSettings.shared(p)));
        files.add(new RenderedFile(layout.etc() + "/" + mcp.settingsFile(), ServiceSettings.mcp(p)));
        files.add(new RenderedFile(layout.etc() + "/" + admin.settingsFile(), ServiceSettings.admin(p)));
        return List.copyOf(files);
    }

    /**
     * The sources of {@code spring.config.import} of a service, below the one directory of the installation: the settings
     * file the installer renders (required), the settings file of the service (required), the operator's own settings
     * ({@code local.yaml}, optional, never overwritten), the registration of the JDBC driver and the folders of the secrets that
     * this service needs ({@link Service#secretFolders}, each a configuration tree of its own; the secret directory itself is
     * never a tree, so a service never has the secrets of another in its {@code Environment}). Later sources win. The command
     * line tool imports the same sources without the file of a service and with the folders it needs itself (its
     * {@code application.properties}), so it sees what the services see of the settings.
     */
    static String configImport(DeployLayout layout, Service service) {
        String etc = layout.etc();
        StringBuilder sources = new StringBuilder("file:" + etc + "/application.yaml,file:" + etc + "/" + service.settingsFile()
                + ",optional:file:" + etc + "/local.yaml,optional:file:" + etc + "/jdbc-driver.yaml");
        for (SecretFolder folder : service.secretFolders()) {
            sources.append(",optional:configtree:").append(layout.secretFolder(folder)).append('/');
        }
        return sources.toString();
    }
}
