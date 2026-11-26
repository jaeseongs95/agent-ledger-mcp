// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.cli.deploy;

import java.nio.file.FileSystem;
import java.util.Map;
import org.agentledger.files.FileKind;

/**
 * What the host decides for the tool that works for an installation: where the installation is when no option says it. The
 * installer of each platform puts the settings of an instance in a place of its own ({@code /etc/<name>} on Linux,
 * {@code C:/ProgramData/<name>/etc} on Windows: {@link DeployLayout}), and a command given as the README gives it
 * ({@code java -jar ledger-cli.jar <command>}) must find them there without an option. The properties of the tool
 * ({@code application.properties}) are written once, with the instance name in them, and take the part of the path that differs by
 * host from the two properties of {@link #settingsPlaceholders}, which {@link HostDefaultsEnvironmentPostProcessor} supplies at the
 * lowest precedence (an option or a setting always wins).
 *
 * <p>The host is told by the file system, the way every other choice of a platform is (ARC-033: never by the name of the operating
 * system): a file system with ACLs and no POSIX permissions is a Windows one; any other is a Linux one, as it was before.
 */
public final class HostDefaults {

    /** The text that comes before the instance name in the settings directory of the host. */
    public static final String SETTINGS_PREFIX = "ledger.host.settings-prefix";
    /** The text that comes after the instance name in the settings directory of the host. */
    public static final String SETTINGS_SUFFIX = "ledger.host.settings-suffix";

    /** Stands for the instance name while the layout is asked for the form of its settings directory. */
    private static final String NAME = "{name}";

    private HostDefaults() {
    }

    /** The properties of the host of {@code fileSystem}, which together with the instance name are its settings directory. */
    public static Map<String, Object> settingsPlaceholders(FileSystem fileSystem) {
        return settingsPlaceholders(FileKind.of(fileSystem));
    }

    /** The properties of a kind of host: {@code /etc/} and nothing for POSIX, {@code C:/ProgramData/} and {@code /etc} for ACLs. */
    static Map<String, Object> settingsPlaceholders(FileKind kind) {
        DeployLayout layout = kind == FileKind.ACL ? DeployLayout.windows(NAME) : DeployLayout.linux(NAME);
        String etc = layout.etc();
        int name = etc.indexOf(NAME);
        return Map.of(SETTINGS_PREFIX, etc.substring(0, name), SETTINGS_SUFFIX, etc.substring(name + NAME.length()));
    }
}
